/* gplv3 hjh */

// some confusion about lastPlug vs predecessor
// 'dest' may need rethinking; does a nested Plug really need to know the owner Syn?

AbstractPatchableNode {
	classvar <>useGroup = false;

	var <source, <>args, <rate, <numChannels;
	var <node, <group, watcher;
	var <concreteArgs, argLookup;
	var <antecedents, <descendants;
	var <>lastPlug;
	var <controls;  // flat dictionary of ctl paths --> node-or-plug arrays
	var <synthDesc;

	free { |latency ... why|
		this.freeToBundle.sendOnTime(this.server, latency);
		this.didFree(*why);
	}
	didFree { |... why|
		this.changed(\didFree, *why);
	}

	prepareSource { |bundle, argSource(source)|
		// preparation messages, bundle messages
		// preparePlugSource gives one node per concreteArgs entry
		node = argSource.preparePlugSource(this, bundle, concreteArgs);
		// bundle is a sneaky way to extract a Function's def
		// though probably inadequate for some future requirement
		this.findOutputChannel(bundle, argSource, node);
	}

	source_ { |src, latency|
		var bundle = this.setSourceToBundle(src);
		bundle.sendOnTime(this.server, latency);
	}
	setSourceToBundle { |src, bundle(OSCBundle.new)|
		var oldNode = node, oldDesc = synthDesc;
		var target, addAction;
		// note, this must precede prepareSource!
		#target, addAction = this.setSourceTarget;
		// btw this call overwrites properties, which is not really ideal
		this.prepareSource(bundle, src);
		src.preparePlugBundle(this, bundle, concreteArgs, target, addAction);
		if(oldDesc.notNil and: { oldDesc.hasGate }) {
			bundle.add(oldNode.releaseMsg)
		} {
			bundle.add(oldNode.freeMsg)
		};
		source = src;
		^bundle
	}

	findOutputChannel { |bundle, src, node|
		var coll, desc, msg, io;
		case
		{ src.isSymbol or: { src.isString } } {
			desc = SynthDescLib.at(source.asSymbol);
		}
		{ src.isFunction } {
			// oops, must communicate def back to here
			// I'm gonna try something naughty though
			// this is being called immediately after preparePlugSource
			// so the latest prep message should be for this function
			// and, only one of them -- '.choose' is a LOL
			// but OK for a single-element unordered collection

			// this should always exist though
			// because this is called after registering in SynthDefTracker
			coll = SynthDefTracker.findCollFor(node, src);
			if(coll.notNil and: { coll[\rate].notNil }) {
				rate = coll[\rate];
				numChannels = coll[\numCh];
				// got answer from cache; leave 'desc' nil
			} {
				msg = bundle.preparationMessages.last;
				if(msg[0] == \d_recv) {
					desc = SynthDesc.readFile(CollStream(msg[1])).choose;
				};
			};
		};
		if(desc.notNil) {
			io = desc.outputs.detect { |io|
				#[out, i_out].includes(io.startingChannel)
			};
			if(io.notNil) {
				rate = io.rate;
				numChannels = io.numberOfChannels;
				if(coll.notNil) {
					coll[\rate] = rate;
					coll[\numCh] = numChannels;
				};
			};
			synthDesc = desc;
		};  // else don't touch the user's rate / numChannels
	}


	*initClass {
		StartUp.add {
			Event.addEventType(\syn, { |server|
				var freqs, lag, strum, sustain;
				var bndl, oscBundles, addAction, sendGate, ids, i;
				var msgFunc, instrumentName, offset, strumOffset, releaseOffset;

				// note, detunedFreq not supported
				freqs = ~freq.value;

				// msgFunc gets the synth's control values from the Event
				msgFunc = ~getMsgFunc.valueEnvir;
				instrumentName = ~synthDefName.valueEnvir;

				sendGate = ~sendGate ? ~hasGate;

				// update values in the Event that may be determined by functions
				~freq = freqs;
				~amp = ~amp.value;
				~sustain = sustain = ~sustain.value;
				lag = ~lag;
				offset = ~timingOffset;
				strum = ~strum;
				~server = server;
				~latency = ~latency ?? { server.latency };  // seriously...?
				~isPlaying = true;
				addAction = Node.actionNumberFor(~addAction);

				// compute the control values and generate OSC commands
				bndl = msgFunc.valueEnvir;

				bndl.pairsDo { |key, value, i|
					var plugKey = (key.asString ++ "Plug").asSymbol;
					var plug = plugKey.envirGet;
					if(plug.canMakePlug) {
						bndl[i+1] = plug.dereference.valueEnvir(value)
					};
				};

				~group = ~group.value;
				// why? because ~group is (by default) the defaultGroup's ID, not the object
				~group = Group.basicNew(~server, ~group.asNodeID);
				bndl = bndl.flop;
				oscBundles = Array(bndl.size);
				~syn = bndl.collect { |args|
					var n = Syn.basicNew(instrumentName, args, ~group, ~addAction);
					oscBundles.add(n.prepareToBundle);
					n.registerNodes  // returns n
				};

				// schedule when the bundles are sent

				if (strum == 0) {
					{
						var start = thisThread.seconds;
						oscBundles.do { |bndl|
							bndl.doPrepare(server, inEnvir {
								var latency;
								latency = ~latency + start - thisThread.seconds;
								~schedBundleArray.(lag, offset, server,
									bndl.messages, latency);
							});
						};
					}.fork(SystemClock);
					if (sendGate) {
						~schedBundleArray.(
							lag,
							sustain + offset,
							server,
							[15 /* \n_set */,
								~syn.collect { |n| n.node.nodeID },
								\gate, 0
							].flop,
							~latency
						);
					}
				} {
					ids = ~syn.collect { |syn| syn.node.nodeID };
					if (strum < 0) {
						oscBundles = oscBundles.reverse;
						ids = ids.reverse;
					};
					strumOffset = Array.series(~syn.size, offset, strum.abs);
					{
						oscBundles.do { |oscb, i|
							var start = thisThread.seconds;
							oscb.doPrepare(server, inEnvir {
								var latency;
								latency = ~latency + start - thisThread.seconds;
								~schedBundleArray.(lag,
									offset,
									server, oscBundles[i].messages, latency
								);
							});
							strum.abs.wait;
						};
					}.fork(thisThread.clock);
					if (sendGate) {
						if (~strumEndsTogether) {
							releaseOffset = sustain + offset
						} {
							releaseOffset = sustain + strumOffset
						};
						~schedBundleArray.(
							lag, releaseOffset, server,
							[15 /* \n_set */, ids, \gate, 0].flop,
							~latency
						);
					}
				}
			});
			Event.addEventType(\synOn, { |server|
				var freqs, lag, strum, sustain;
				var bndl, oscBundles, addAction, sendGate, ids, i;
				var msgFunc, instrumentName, offset, strumOffset, releaseOffset;

				// note, detunedFreq not supported
				freqs = ~freq.value;

				// msgFunc gets the synth's control values from the Event
				msgFunc = ~getMsgFunc.valueEnvir;
				instrumentName = ~synthDefName.valueEnvir;

				sendGate = ~sendGate ? ~hasGate;

				// update values in the Event that may be determined by functions
				~freq = freqs;
				~amp = ~amp.value;
				~sustain = sustain = ~sustain.value;
				lag = ~lag;
				offset = ~timingOffset;
				strum = ~strum;
				~server = server;
				~latency = ~latency ?? { server.latency };  // seriously...?
				~isPlaying = true;
				addAction = Node.actionNumberFor(~addAction);

				// compute the control values and generate OSC commands
				bndl = msgFunc.valueEnvir;

				bndl.pairsDo { |key, value, i|
					var plugKey = (key.asString ++ "Plug").asSymbol;
					var plug = plugKey.envirGet;
					if(plug.canMakePlug) {
						bndl[i+1] = plug.dereference.valueEnvir(value)
					};
				};

				~group = ~group.value;
				// why? because ~group is (by default) the defaultGroup's ID, not the object
				~group = Group.basicNew(~server, ~group.asNodeID);
				bndl = bndl.flop;
				oscBundles = Array(bndl.size);
				~syn = bndl.collect { |args|
					var n = Syn.basicNew(instrumentName, args, ~group, ~addAction);
					oscBundles.add(n.prepareToBundle);
					n.registerNodes  // returns n
				};
				{
					var start = thisThread.seconds;
					oscBundles.do { |bndl|
						bndl.doPrepare(server, inEnvir {
							var latency;
							latency = ~latency + start - thisThread.seconds;
							~schedBundleArray.(lag, offset, server,
								bndl.messages, latency);
						});
					};
				}.fork(SystemClock);
			});
			Event.addEventType(\synOff, { |server|
				if(~hasGate) {
					~syn.do { |syn|
						syn.release(
							~latency ?? { server.latency },
							min(0.0, ~gate ?? { 0.0 })  // accept release times
						);
					};
				} {
					~syn.do { |syn|
						syn.free(~latency ?? { server.latency });
					};
				}
			});

			// ~syn should be an array of Syns
			Event.addEventType(\synSet, { |server|
				var freqs, bndl;
				var syn = ~syn.asArray;

				freqs = ~freq = ~freq.value;
				~server = server;
				~amp = ~amp.value;
				bndl = ~args.envirPairs.flop;

				bndl.do { |args, i|
					~schedBundleArray.value(~lag, ~timingOffset, server,
						syn.wrapAt(i).setToBundle(nil, *args).messages,
						~latency
					);
				};
			});

			Event.addEventType(\monoSyn, { |server|
				var freqs, lag, strum, sustain;
				var bndl, oscBundles, addAction, sendGate, ids, i;
				var msgFunc, instrumentName, offset, strumOffset, releaseOffset;

				// note, detunedFreq not supported
				freqs = ~freq.value;

				// msgFunc gets the synth's control values from the Event
				msgFunc = ~getMsgFunc.valueEnvir;
				instrumentName = ~synthDefName.valueEnvir;

				sendGate = ~sendGate ? ~hasGate;

				// update values in the Event that may be determined by functions
				~freq = freqs;
				~amp = ~amp.value;
				~sustain = sustain = ~sustain.value;
				lag = ~lag;
				offset = ~timingOffset;
				strum = ~strum;
				~server = server;
				~latency = ~latency ?? { server.latency };  // seriously...?
				~isPlaying = true;
				addAction = Node.actionNumberFor(~addAction);

				// compute the control values and generate OSC commands
				// assumes caller has prepared ~args *with* SynthDesc arg names
				// special case event type, should only be used with Pmsyn
				// which takes care of this
				bndl = ~args.envirPairs;

				bndl.pairsDo { |key, value, i|
					var plugKey = (key.asString ++ "Plug").asSymbol;
					var plug = plugKey.envirGet;
					if(plug.canMakePlug) {
						bndl[i+1] = plug.dereference.valueEnvir(value)
					};
				};

				~group = ~group.value;
				// why? because ~group is (by default) the defaultGroup's ID, not the object
				~group = Group.basicNew(~server, ~group.asNodeID);
				bndl = bndl.flop;
				oscBundles = Array(bndl.size);
				~syn = bndl.collect { |args|
					var n = Syn.basicNew(instrumentName, args, ~group, ~addAction);
					oscBundles.add(n.prepareToBundle);
					n.registerNodes  // returns n
				};
				{
					var start = thisThread.seconds;
					oscBundles.do { |bndl|
						bndl.doPrepare(server, inEnvir {
							var latency;
							latency = ~latency + start - thisThread.seconds;
							~schedBundleArray.(lag, offset, server,
								bndl.messages, latency);
						});
					};
				}.fork(SystemClock);

				~id = ~syn.collect { |syn| syn.node.nodeID };
				~updatePmono.(~id, server);
			});
		}
	}
}
