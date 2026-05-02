/* gplv3 hjh */

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

	makeSetBundle { |selector(\set), bundle(OSCBundle.new) ... argList|
		var nodes = IdentityDictionary.new;
		var doMap = { |map, key, value|
			var i, msg;
			map.keysValuesDo { |ctlname, set|
				set.do { |object|
					var old;
					old = object.argAt(ctlname);
					if(object !== this) {
						nodes[object.asNodeID] = nodes[object.asNodeID].add(
							object.perform(selector, ctlname, value)
						);
						// Plug 'set' gets updated in the plug object, not here
						// so I don't need an updateArgs
					} {
						nodes[this.asNodeID] = nodes[this.asNodeID].add(
							node.perform(selector, ctlname, value)
						);
						// btw this should be true only once b/c a Set can't have dupes
						this.updateOneArg(ctlname, value);
					};
					if(old.isKindOf(Plug)) {
						// prevent bus leakage
						old.bus.removeClient(this);
						old.freeToBundle(bundle);
					};
				}
			};
		};
		var doMapPlug = { |key, value|
			var obj = this.objectForPath(key);
			var ctlname;
			var old, new;
			if(obj.notNil) {
				ctlname = key.asString.split($/).last.asSymbol;
				old = obj.argAt(ctlname);
				new = obj.setPlugToBundle(ctlname, value, bundle);
				obj.updateOneArg(ctlname, new);
				if(old.isKindOf(Plug)) {
					// prevent bus leakage
					old.bus.removeClient(this);
					old.freeToBundle(bundle);
				};
				// 'controls' map tree for this root object
				// may have changed; for now, just delete it
				// (it will be rebuilt on demand when needed)
				this.invalidateControl(key);
			};
		};
		selector = (selector.asString ++ "Msg").asSymbol;
		argList.pairsDo { |key, value|
			var map;
			key = key.asSymbol;
			map = controls[key];
			if(value.isKindOf(Plug).not) {
				value = value.asControlInput;
				if(map.notNil) {
					doMap.(map, key, value);
				} {
					// must start with the root of this tree
					this.addControl(key.asString.split($/).first.asSymbol);
					map = controls[key];
					if(map.notNil) {
						doMap.(map, key, value);
					};
				}
			} {
				doMapPlug.(key, value)
			};
		};
		nodes.keysValuesDo { |id, msgs|
			var args = Array(16);
			msgs.do { |msg|
				(msg.size - 2).do { |i|
					args = args.add(msg[i+2]);
				};
			};
			bundle.add([15, id] ++ args);
		};
		^bundle
	}
	setToBundle { |bundle(OSCBundle.new) ... args|
		^this.makeSetBundle(\set, bundle, *args)
	}
	setnToBundle { |bundle(OSCBundle.new) ... args|
		^this.makeSetBundle(\setn, bundle, *args)
	}
	set { |... args|
		this.setToBundle(nil, *args).sendOnTime(this.server, nil)
	}
	setn { |... args|
		this.setnToBundle(nil, *args).sendOnTime(this.server, nil)
	}

	// when you '.set' we need to keep a list of paths --> nodes to set
	// IDict[
	//     \nameToSet -> IDict[
	//         \synthNameToSet -> ISet[Syn, or Plug]
	//     ]
	// ]
	// addControl builds this dictionary on demand
	// every node in the tree knows its part of the dictionary
	// however, it's expected you'll '.set' on the Syn most of the time
	addControl { |name|
		var mapKey;
		var a;
		var mapping, childMap;
		mapping = this.scanMaps(name);
		mapKey = mapping.followControlPathLinks(name);
		if(controls[name].isNil) {
			controls[name] = IdentityDictionary.new;
		};
		mapKey.do { |mpk|
			// mpk is a full path to the target arg, from this node
			// controls entries should key off of the arg name to set
			// mapping.addMap(name, )
			controls[name].addAt(mpk.asString.basename.asSymbol, this.objectForPath(mpk));
		};
		a = this.argAt(name);
		if(a.isKindOf(Plug)) {
			a.controlNames.do { |cn|
				a.addControl(cn);
			};
			a.controls.keysValuesDo { |k, v|
				var path = (name ++ "/" ++ k).asSymbol;
				if(controls[path].isNil) {
					controls[path] = IdentityDictionary.new;
				};
				controls[path].putAll(v);
			};
		};
		^mapping
	}
	scanMaps { |name|
		var mapping = ControlNameMap.new;
		var a = this.argAt(name);
		var mapName;

		if(a.isKindOf(Plug)) {
			// two relevant cases:
			// 1. a has a map matching 'name'
			//    'name' is my (parent's) controlname; mapName is child's
			// 2. a has a control matching 'name' (map should override this)
			case
			{ mapName = a.map.tryPerform(\at, name); mapName.notNil } {
				mapping.addAt(name, (name ++ "/" ++ mapName).asSymbol);
				// prMaps.addAt(makeKey.(path, name), makeKey.(childPath, mapName));
			}
			// chain without changing name
			{ a.controlNames.tryPerform(\includes, name) ?? { false } } {
				mapping.addAt(name, (name ++ "/" ++ name).asSymbol);
				// prMaps.addAt(makeKey.(path, name), makeKey.(childPath, name));
			};

			a.controlNames.do { |cn|
				// remember that cn is the name *within 'a'* to examine
				// a should **NOT** be the arg value that cn points to!
				var childMap = a.scanMaps(cn).prepend(name);
				mapping.merge(childMap);
			};
		};

		// and sibling check
		concreteArgs.pairsDo { |name2, value|
			var mapAt, childMap; // , key, childPath;
			if(name2 != name and: {
				value.isKindOf(Plug) and: {
					// looking for a Plug whose map contains the parent-level param = name
					mapAt = value.map.tryPerform(\at, name);
					mapAt.notNil
				}
			}) {
				// entry to be added should look like:
				// name --> ISet[name2/mapAt]
				mapping.addAt(name, (name2 ++ "/" ++ mapAt).asSymbol);

				// this line is because the sibling map doesn't cancel the self-map
				mapping.addAt(name, name);
				childMap = value.scanMaps(mapAt).prepend(name2);
				mapping.merge(childMap);
			};
		};
		^mapping
	}

	invalidateControl { |path|
		path = path.asString;
		if(controls.notNil) {
			controls.keysDo { |key|
				if(key.asString.beginsWith(path)) {
					controls.removeAt(key)
				}
			}
		}
	}
	initArgLookup { |args|
		argLookup = IdentityDictionary.new;
		args.pairsDo { |key, value|
			argLookup.put(key, value);
		};
	}
	argAt { |key|
		if(argLookup.isNil) { this.initArgLookup(concreteArgs) };
		^argLookup[key]
	}
	argAtPath { |path|
		var obj = this;
		var key, value;
		path = Pseq(path.asString.split($/).collect(_.asSymbol), 1).asStream;
		while {
			key = path.next;
			key.notNil and: {
				value = obj.tryPerform(\argAt, key);
				if(value.isNil) { ^nil } { true }
			}
		} {
			obj = value;
		};
		^obj
	}

	updateOneArg { |key, value|
		var i;
		if(argLookup.notNil) {
			argLookup[key] = value;
		};
		i = args.tryPerform(\indexOf, key);
		if(i.notNil) {
			args[i + 1] = value;
		} {
			args = args.add(key).add(value);
		};
		i = concreteArgs.tryPerform(\indexOf, key);
		if(i.notNil) {
			concreteArgs[i + 1] = value;  // change later
		} {
			this.appendConcreteArg(key, value);
		};
	}
	updateArgs { |setArgs|
		var i;
		setArgs.pairsDo { |key, value|
			this.updateOneArg(key, value)
		}
	}

	controlNames {
		^if(synthDesc.notNil) { synthDesc.controlNames }
	}

	// change to recursive approach?
	objectForPath { |path|
		var obj = this;
		var key, value;
		// because we need to stop one item early, not reach the actual arg value
		// the Ref skulduggery is because a Pseq list cannot be empty
		path = Pseq(
			path.asString.split($/).collect(_.asSymbol)
			.drop(-1).add(Ref(\end)),
			1
		).asStream;
		while {
			key = path.next;
			if(key.isKindOf(Ref)) { ^obj };
			key.notNil and: {
				value = obj.tryPerform(\argAt, key);
				if(value.isNil) { ^nil } { true }
			}
		} {
			obj = value;
		};
		^obj
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
