/* gplv3 hjh */

// TODO: Is it safe to 'release' always?

/*
Intended flow:
- Syn is the driver.
- When a Syn releases (n_end), it broadcasts didFree.
- Dependants should include:
- All Plug subclasses.
- The AutoReleaseBus? (Yes. Plugs may in theory end early.)
Plug:asPluggable takes care of this.
- So the \didFree notification should force-kill all the Plugs,
and remove itself from the AutoReleaseBus, at which point, the bus goes away too.
*/

AutoReleaseBus : Bus {
	var clients;

	free { |... why|
		super.free;
		this.changed(\didFree, *why);
	}

	addClient { |client|
		if(clients.isNil) { clients = IdentitySet.new };
		clients.add(client)
	}

	removeClient { |client|
		clients.remove(client);
		if(clients.notNil and: { clients.isEmpty }) {
			this.free(\clientRemoved, client);
		}
	}

	update { |obj, what ... args|
		switch(what)
		{ \didFree } {
			obj.removeDependant(this);
			this.removeClient(obj);
		}
	}
}

// a modulation or signal source for a target Syn

/* problem:

p = Pbind(
\type, \syn,
\instrument, \xyz,
\lfo, Plug { LFDNoise3.kr(0.1) }
)

You have one instance of Plug but you need one for each event.
So Plug will have to manufacture an instance of something.
Another class? Or 'copy' the Plug and set a flag?
*/

Plug {
	var <source, <args, <rate, <numChannels;
	var <node, <dest;
	var <bus;  // use AutoReleaseBus
	// var nodes;
	var <antecedents, <descendants;
	var <isConcrete = false;

	// Plug also needs to be told the destination
	// I think this is asPluggable?

	*new { |source, args, rate = \control, numChannels = 1|
		^super.newCopyArgs(source, args, rate, numChannels).init
	}

	*shared { |source, args, rate = \control, numChannels = 1|
		^this.new(source, args, rate, numChannels).prConcrete_(true)
	}

	init {
		// nodes = IdentitySet.new;
		antecedents = IdentitySet.new;
		descendants = IdentitySet.new;
	}

	free { |latency|
		if(node.notNil) {
			node.server.sendBundle(latency,
				[\error, -1],
				node.freeMsg,
				[\error, -2]
			);
			bus.removeClient(this);
			bus = node = nil;
		};
		// dest is only the latest descendant
		// dest.removeDependant(this);
		descendants.do(_.removeDependant(this));  // not sure this is needed?
		this.changed(\didFree, \plugFreed);
		antecedents.do { |plug| plug.descendants.remove(this) };
		descendants.do { |plug| plug.antecedents.remove(this) };
		// nodes.do { |node| node.removeDependant(this) };
	}

	asPluggable { |argDest, downstream, bundle, controlDict|
		var argList;
		if(isConcrete) {
			// [argDest, downstream, bundle, controlDict.proto, controlDict].debug("Plug:asPluggable");
			dest = argDest;
			descendants.add(downstream);
			downstream.antecedents.add(this);
			if(bus.isNil) {
				// only one synth per plug, for now
				argList = [
					args.asOSCPlugArray(dest, this, bundle, controlDict)
				];
				// preparation messages, bundle messages
				// preparePlugSource gives one node per argList entry
				node = source.preparePlugSource(this, bundle, argList)
				.at(0);  // one synth for now
				// bundle is a sneaky way to extract a Function's def
				// though probably inadequate for some future requirement
				this.findOutputChannel(bundle);
				bus = AutoReleaseBus.perform(rate, dest.server, numChannels);
				argList[0] = argList[0] ++ [out: bus, i_out: bus];
				source.preparePlugBundle(
					this,
					bundle,
					argList,
					controlDict,
					*dest.bundleTarget
				);
				dest.lastPlug = this;  // only set if this time made a node
			};

			// if(nodes.includes(dest).not) {
			// 	nodes.add(dest);
			// };
			// these are all Sets so multiple adds are OK (no redundancy)
			bus.addClient(this);
			bus.addClient(downstream);
			downstream.addDependant(this);
			downstream.addDependant(bus);
			^this.asMap;
		} {
			^this.concreteInstance.asPluggable(argDest, downstream, bundle, controlDict);
		}
	}

	nodeAt { |i| ^node }  // just the one
	server { ^dest.server }
	group { ^dest.group }

	set { |... args|
		node.set(*args);
	}
	setn { |... args|
		node.setn(*args);
	}
	setMsg { |... args|
		^node.setMsg(*args);
	}
	setnMsg { |... args|
		^node.setnMsg(*args);
	}

	findOutputChannel { |bundle|
		var desc, msg, io;
		case
		{ source.isSymbol or: { source.isString } } {
			desc = SynthDescLib.at(source.asSymbol);
		}
		{ source.isFunction } {
			// oops, must communicate def back to here
			// I'm gonna try something naughty though
			// this is being called immediately after preparePlugSource
			// so the latest prep message should be for this function
			// and, only one of them -- '.choose' is a LOL
			// but OK for a single-element unordered collection
			msg = bundle.preparationMessages.last;
			if(msg[0] == \d_recv) {
				desc = SynthDesc.readFile(CollStream(msg[1])).choose;
			}
		};
		if(desc.notNil) {
			io = desc.outputs.detect { |io|
				#[out, i_out].includes(io.startingChannel)
			};
			if(io.notNil) {
				rate = io.rate;
				numChannels = io.numberOfChannels;
			};

			// also slightly cheating, but I have the def here, so...
			// desc.debug("adding controls for desc");
			// dest.controls.proto[\path].debug("path is currently");
			desc.controls.do { |cn|
				dest.addControl(this, cn.name);
			};
		};  // else don't touch the user's rate / numChannels
	}

	asMap {
		var rateLetter;
		if(bus.isNil) {
			^nil;
		};
		rateLetter = bus.rate.asString.first;
		^if(bus.numChannels > 1) {
			Array.fill(bus.numChannels, { |i|
				(rateLetter ++ (bus.index + i)).asSymbol
			})
		} {
			bus.asMap
		}
	}
	asControlInput { ^this.asMap }

	concreteInstance {
		^this.class.new(source, args, rate, numChannels).prConcrete_(true)
	}

	prConcrete_ { |bool(false)|
		isConcrete = bool
	}

	update { |obj, what ... args|
		switch(what)
		{ \didFree } {
			descendants.remove(obj);
			obj.removeDependant(this);
			if(descendants.isEmpty) {
				this.free;
			};
		}
	}

	printOn { |stream|
		stream << this.class.name << "[" << this.hash.asHexString(8) << "]";
	}
}

// like Synth but makes a bundle, including plug sources
// for ad hoc sources, requires some latency

// maybe MixedBundle, to handle prep messages
// in that case we need to pass the bundle down through the asOSCPlugArray chain

// also need to keep a dictionary of settable controls
// only non-plug controls

Syn {
	var <>source, <>args, <>target, <>addAction;

	// unlike Synth, does *not* send
	// you need to 'play' it
	// this way, one template can play multiple complexes
	*new { |source, args, target(Server.default.defaultGroup), addAction = \addToHead|
		^super.newCopyArgs(source, args, target, addAction)
	}

	play { |argTarget, argAddAction, argArgs, latency|
		^SynPlayer(
			source,
			argArgs ?? { args },  // merge?
			argTarget ?? { target },
			argAddAction ?? { addAction },
			latency ?? { target.server.latency }
		)
	}

	eventPlay { |argTarget, argAddAction, argArgs, latency|
		^SynPlayer(
			source,
			argArgs ?? { args },  // merge?
			argTarget ?? { target },
			argAddAction ?? { addAction },
			latency ?? { target.server.latency },
			\event
		)
	}
}

SynPlayer {
	var <>source, <>args, <>target, <>addAction;
	var <node, <group, watcher, nodeIDs;
	var <antecedents;
	var <>lastPlug;
	var <controls;  // flat dictionary of ctl paths --> node-or-plug arrays

	*new { |source, args, target, addAction, latency, style(\synth)|
		^super.newCopyArgs(source, args, target, addAction).init(latency, style);
	}

	*basicNew { |source, args, target, addAction|
		^super.newCopyArgs(source, args, target, addAction)
	}

	init { |latency, style|
		var bundle = this.prepareToBundle(style);
		this.sendBundle(bundle, latency);
	}

	prepareToBundle { |style|
		var argList;
		var bundle = OSCBundle.new;

		if(antecedents.isNil) { antecedents = IdentitySet.new };

		if(#[synthgroup, eventgroup].includes(style)) {
			group = Group.basicNew(target.server);  // note, children can get this from 'dest'
			bundle.add(group.newMsg(target, addAction));
		};

		// node = Synth.basicNew(defName, target.server);
		// bundle.add(node.newMsg(group, argList, \addToTail));
		// question for later: this is now basically just like Plug
		// so do we even need a top-level object?

		controls = IdentityDictionary.new.proto_(
			IdentityDictionary[\path -> []]
		);

		if(#[event, eventgroup].includes(style)) {
			argList = this.multiChannelExpand(args);
		} {
			argList = [args];
		};
		argList = argList.collect { |a| a.asOSCPlugArray(this, this, bundle, controls) };

		// maybe need to refactor this
		// all types of sources should flatten to a 'defName'?
		node = source.preparePlugSource(this, bundle, argList);
		this.addControls(bundle);
		source.preparePlugBundle(
			this, bundle, argList, controls,
			*this.bundleTarget
		);

		^bundle
	}

	sendBundle { |bundle, latency|
		bundle.sendOnTime(target.server, latency ?? { target.server.latency });
		this.registerNodes;
	}

	registerNodes {
		nodeIDs = IdentitySet.new;
		watcher = node.collect { |n|
			nodeIDs.add(n.nodeID);
			OSCFunc({ |msg|
				nodeIDs.remove(msg[1]);
				if(nodeIDs.isEmpty) {
					this.free(nil, \nodeEnded);  // that simple?
				};
			}, '/n_end', target.server.addr, argTemplate: [n.nodeID])
			// needs to survive cmd-.
			.fix.oneShot;
		};
	}

	makeSetBundle { |selector(\set) ... args|
		var bundle = List.new;
		selector = (selector.asString ++ "Msg").asSymbol;
		args.pairsDo { |key, value|
			var map = controls[key];
			value = value.asControlInput;
			if(map.notNil) {
				map.keysValuesDo { |ctlname, set|
					set.do { |object|
						if(object !== this) {
							bundle.add(object.perform(selector, ctlname, value))
						} {
							node.do { |n|
								bundle.add(n.perform(selector, ctlname, value))
							};
						}
					}
				}
			} {
				// fallback, just try at the head
				node.do { |n|
					bundle.add(n.perform(selector, key, value))
				};
			}
		};
		^bundle
	}
	setToBundle { |... args|
		^this.makeSetBundle(\set, *args)
	}
	setnToBundle { |... args|
		^this.makeSetBundle(\setn, *args)
	}
	set { |... args|
		this.server.sendBundle(nil, *this.setToBundle(*args))
	}
	setn { |... args|
		this.server.sendBundle(nil, *this.setnToBundle(*args))
	}

	moveToHead { |group|
		var prev;
		this.allNodes.do { |n|
			if(prev.isNil) {
				n.moveToHead(group);
			} {
				n.moveAfter(prev);
			};
			prev = n;
		};
	}
	moveToTail { |group|
		var prev;
		this.allNodes.do { |n|
			if(prev.isNil) {
				n.moveToTail(group);
			} {
				n.moveAfter(prev);
			};
			prev = n;
		};
	}
	moveBefore { |aNode|
		var prev;
		this.allNodes.do { |n|
			if(prev.isNil) {
				n.moveBefore(aNode);
			} {
				n.moveAfter(prev);
			};
			prev = n;
		};
	}
	moveAfter { |aNode|
		var prev;
		this.allNodes.do { |n|
			if(prev.isNil) {
				n.moveAfter(aNode);
			} {
				n.moveAfter(prev);
			};
			prev = n;
		};
	}
	allNodes {
		var nodes = LinkedList.new;
		var added = IdentitySet.new;
		var traverse = { |set|
			set.do { |plug|
				traverse.(plug.antecedents);
				if(added.includes(plug.node).not) {
					nodes.add(plug.node);
					added.add(plug.node);
				};
			};
		};
		traverse.(antecedents);
		node.do { |n| nodes.add(n) };
		^nodes
	}

	addControls { |bundle|
		var desc, msg, io;
		case
		{ source.isSymbol or: { source.isString } } {
			desc = SynthDescLib.at(source.asSymbol);
		}
		{ source.isFunction } {
			msg = bundle.preparationMessages.last;
			if(msg[0] == \d_recv) {
				desc = SynthDesc.readFile(CollStream(msg[1])).choose;
			}
		};
		if(desc.notNil) {
			desc.controls.do { |cn|
				this.addControl(this, cn.name);
			};
		};  // else don't touch the user's rate / numChannels
	}

	// name/subname --> a specific plug
	// *name/subname --> that plug and all children
	// name --> the SynPlayer
	// *name --> everywhere
	// maybe make these Sets?
	addControl { |object, name|
		var path = controls.proto[\path] ++ [name], key;
		var addTo = { |dict, name, object|
			if(dict[name].isNil) {
				dict[name] = IdentitySet.new;
			};
			dict[name].add(object);
		};
		key = path.join($/).asSymbol;
		// add single referent
		if(controls[key].isNil) { controls[key] = IdentityDictionary.new };
		addTo.(controls[key], name, object);
		// all parent levels
		path.size.do {
			key = ("*" ++ path.join($/)).asSymbol;
			if(controls[key].isNil) { controls[key] = IdentityDictionary.new };
			addTo.(controls[key], name, object);
			if(path.size > 1) { path.removeAt(path.size - 2) };
		};
	}

	server { ^target.server }
	dest { ^this }
	nodeAt { |i| ^node[i] }
	bundleTarget {
		^case
		{ group.notNil } {
			[group, \addToTail]
		}
		{ lastPlug.notNil } {
			[lastPlug.node, \addAfter]
		} {
			[target, addAction]
		}
	}
	rate { ^\audio }

	free { |latency ... why|
		this.server.sendBundle(latency, *this.freeToBundle);
		this.didFree(*why);
	}

	freeToBundle {
		var bundle = Array(node.size + 2).add([error: -1]);
		node.do { |n| bundle.add(n.freeMsg) };
		^bundle.add([error: -2])
	}

	release { |latency, gate = 0|
		this.server.sendBundle(latency, *this.releaseToBundle(gate));
	}

	releaseToBundle { |gate = 0|
		^[15, node.collect(_.nodeID), \gate, gate].flop
	}

	didFree { |... why|
		this.changed(\didFree, *why);
	}

	multiChannelExpand { |args|
		// duplicate any non-shared plugs, to preserve independence
		^this.duplicatePlugs(args).flop
	}

	duplicatePlugs { |args|
		^args.collect { |item|
			case
			{ item.isSequenceableCollection } {
				this.duplicatePlugs(item)
			}
			{ item.isKindOf(Plug) } {
				if(item.isConcrete) { item } { item.copy }
			}
			{ item }
		}
	}

	*initClass {
		StartUp.add {
			Event.addEventType(\syn, { |server|
				var freqs, lag, strum, sustain;
				var bndl, addAction, sendGate, ids, i;
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

				~group = ~group.value;
				// why? because ~group is (by default) the defaultGroup's ID, not the object
				~group = Group.basicNew(~server, ~group);
				~syn = SynPlayer.basicNew(instrumentName, bndl, ~group, ~addAction);
				bndl = ~syn.prepareToBundle(\event);
				~syn.registerNodes;

				// schedule when the bundles are sent

				if (strum == 0) {
					{
						var start = thisThread.seconds;
						bndl.doPrepare(server, inEnvir {
							var latency;
							latency = ~latency + start - thisThread.seconds;
							~schedBundleArray.(lag, offset, server, bndl.messages, latency);
						});
					}.fork(SystemClock);
					if (sendGate) {
						~schedBundleArray.(
							lag,
							sustain + offset,
							server,
							[15 /* \n_set */, ~syn.node.collect(_.nodeID), \gate, 0].flop,
							~latency
						);
					}
				} {
					ids = ~syn.node.collect(_.nodeID);
					// I think I can't support this?
					// if (strum < 0) {
					// 	bndl = bndl.reverse;
					// 	ids = ids.reverse
					// };
					strumOffset = Array.series(~syn.node.size, offset, strum.abs);
					i = bndl.messages.size - ~syn.node.size;
					{
						var start = thisThread.seconds;
						bndl.doPrepare(server, inEnvir {
							var latency;
							latency = ~latency + start - thisThread.seconds;
							~schedBundleArray.(lag,
								Array.fill(i, offset) ++ strumOffset,
								server, bndl.messages, latency
							);
						});
					}.fork(SystemClock);
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
				var bndl, addAction, sendGate, ids, i;
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

				~group = ~group.value;
				// why? because ~group is (by default) the defaultGroup's ID, not the object
				~group = Group.basicNew(~server, ~group);
				~syn = SynPlayer.basicNew(instrumentName, bndl, ~group, ~addAction);
				bndl = ~syn.prepareToBundle(\event);
				~syn.registerNodes;
				{
					var start = thisThread.seconds;
					bndl.doPrepare(server, inEnvir {
						var latency;
						latency = ~latency + start - thisThread.seconds;
						~schedBundleArray.(lag, offset, server, bndl.messages, latency);
					});
				}.fork(SystemClock);
			});
			Event.addEventType(\synOff, { |server|
				if(~hasGate) {
					~syn.release(
						~latency ?? { server.latency },
						min(0.0, ~gate ?? { 0.0 })  // accept release times
					);
				} {
					~syn.free(~latency ?? { server.latency });
				}
			});
			// Event.addEventType(\synSet, {
			//
			// });
			// monoSyn?
		}
	}
}

// need to track client SynPlayers and Plugs using an auto-generated synthdef
SynthDefTracker {
	// Dict: server -> defname (symbol) -> Set of clients
	classvar all;

	*initClass {
		all = IdentityDictionary.new;
	}

	*register { |object, defname|
		var server = object.server;
		if(all[server].isNil) {
			all[server] = IdentityDictionary.new;
		};
		defname = defname.asSymbol;
		if(all[server][defname].isNil) {
			all[server][defname] = IdentitySet.new;
		};
		all[server][defname].add(object);
	}

	*release { |object, defname|
		var set = all[object.server];
		if(set.notNil) {
			set = set[defname];
			set.remove(object);
			if(set.isEmpty) {
				object.server.sendMsg(\d_free, defname);
			};
		};
	}
}
