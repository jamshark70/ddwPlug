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
		if(index.notNil) { super.free };
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

map: \sym1 -> \sym2
\sym1 is control name in the parent, if set it should translate to child \sym2
*/

Plug {
	var <source, <>args, <rate, <numChannels;
	var <>map;
	var <node, <dest;
	var <bus;  // use AutoReleaseBus
	var <concreteArgs, argLookup;
	var <antecedents, <descendants;
	var <predecessor;
	var <isConcrete = false;
	var synthDesc;

	// Plug also needs to be told the destination
	// I think this is asPluggable?

	*new { |source, args, rate = \control, numChannels = 1, map|
		^super.newCopyArgs(source, args, rate, numChannels, map).init
	}

	*shared { |source, args, rate = \control, numChannels = 1, map|
		^this.new(source, args, rate, numChannels, map).prConcrete_(true)
	}

	init {
		// nodes = IdentitySet.new;
		antecedents = IdentitySet.new;
		descendants = IdentitySet.new;
	}

	free { |latency|
		this.freeToBundle.sendOnTime(this.server, latency)
	}
	freeToBundle { |bundle(OSCBundle.new)|
		if(node.notNil) {
			bundle.add([\error, -1])
			.add(node.freeMsg)
			.add([\error, -2]);
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
		^bundle
	}

	asPluggable { |argDest, downstream, bundle|
		if(isConcrete) {
			dest = argDest;
			descendants.add(downstream);
			downstream.antecedents.add(this);
			if(bus.isNil) {
				// only one synth per plug, for now
				// array wrapper is for consistency with 'preparePlug...' methods
				concreteArgs = args.asOSCPlugArray(dest, this, bundle);
				// preparation messages, bundle messages
				// preparePlugSource gives one node per concreteArgs entry
				node = source.preparePlugSource(this, bundle, concreteArgs);
				// bundle is a sneaky way to extract a Function's def
				// though probably inadequate for some future requirement
				this.findOutputChannel(bundle, source);
				bus = AutoReleaseBus.perform(rate, dest.server, numChannels);
				concreteArgs = concreteArgs ++ [out: bus, i_out: bus];
				source.preparePlugBundle(
					this,
					bundle,
					concreteArgs.asOSCArgArray,
					*dest.bundleTarget(predecessor, downstream)
				);
				predecessor = dest.lastPlug;
				dest.lastPlug = this;  // only set if this time made a node
			};

			// these are all Sets so multiple adds are OK (no redundancy)
			bus.addClient(this);
			bus.addClient(downstream);
			downstream.addDependant(this);
			downstream.addDependant(bus);
		} {
			^this.concreteInstance.asPluggable(argDest, downstream, bundle);
		}
	}

	server { ^dest.server }
	group { ^dest.group }

	set { |... args|
		node.set(*args);
	}
	setn { |... args|
		node.setn(*args);
	}
	setMsg { |... args|
		this.updateArgs(args);
		^node.setMsg(*args);
	}
	setnMsg { |... args|
		this.updateArgs(args);
		^node.setnMsg(*args);
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
			concreteArgs[i + 1] = value;
		} {
			i = concreteArgs.size - 4;
			concreteArgs = concreteArgs.insert(i, value).insert(i, key);
		};
	}
	updateArgs { |setArgs|
		var i;
		setArgs.pairsDo { |key, value|
			this.updateOneArg(key, value)
		}
	}

	setSourceToBundle { |src, bundle(OSCBundle.new)|
		var oldNode = node, oldDesc = synthDesc;
		var target, addAction;
		node = src.preparePlugSource(this, bundle, concreteArgs);
		if(predecessor.notNil) {
			target = predecessor.node;  // must be a Plug, only one node
			addAction = \addAfter;
		} {
			// if this has no predecessor, then it's the first Plug
			// so the referent is the plug's old node
			target = oldNode;
			addAction = \addBefore;
		};
		src.preparePlugBundle(this, bundle, concreteArgs, target, addAction);
		// btw this call overwrites properties, which is not really good
		this.findOutputChannel(bundle, src);
		if(oldDesc.notNil and: { oldDesc.hasGate }) {
			bundle.add(oldNode.releaseMsg)
		} {
			bundle.add(oldNode.freeMsg)
		};
		source = src;
		^bundle
	}
	source_ { |src, latency|
		var bundle = this.setSourceToBundle(src);
		bundle.sendOnTime(this.server, latency);
	}

	setPlugToBundle { |key, plug, bundle(OSCBundle.new)|
		var new = plug.asPluggable(dest, this, bundle);
		this.updateArgs([key, new]);
		bundle.add(node.setMsg(key, new.asMap));
		^new
	}

	findOutputChannel { |bundle, src|
		var desc, msg, io;
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
			synthDesc = desc;

			// // also slightly cheating, but I have the def here, so...
			// // desc.debug("adding controls for desc");
			// // dest.controls.proto[\path].debug("path is currently");
			// desc.controls.do { |cn|
			// 	dest.addControl(this, cn.name);
			// };
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
		^this.class.new(source, args, rate, numChannels, map).prConcrete_(true)
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

	initArgLookup { |args|
		argLookup = IdentityDictionary.new;
		args.pairsDo { |key, value|
			// keys is an array but all elements should be the same
			argLookup.put(key, value);
		};
	}
	argAt { |key|
		if(argLookup.isNil) {
			this.initArgLookup(concreteArgs);
		};
		^argLookup[key]
	}
	controlNames {
		^if(synthDesc.notNil) { synthDesc.controlNames }
	}

	canMakePlug { ^true }
}

// like Synth but makes a bundle, including plug sources
// for ad hoc sources, requires some latency

// maybe MixedBundle, to handle prep messages
// in that case we need to pass the bundle down through the asOSCPlugArray chain

// also need to keep a dictionary of settable controls
// only non-plug controls

Syn {
	classvar <>useGroup = false;

	var <source, <>args, <>target, <>addAction;
	var <node, <group, watcher;
	var <concreteArgs, argLookup;
	var <antecedents;
	var <>lastPlug;
	var <controls;  // flat dictionary of ctl paths --> node-or-plug arrays
	var <synthDesc;

	*new { |source, args, target(Server.default.defaultGroup), addAction(\addToTail), latency|
		^super.newCopyArgs(source, args, target, addAction).play(latency);
	}

	*basicNew { |source, args, target(Server.default.defaultGroup), addAction(\addToTail)|
		^super.newCopyArgs(source, args, target, addAction)
	}

	*newByArgPaths { |source, args, target(Server.default.defaultGroup), addAction(\addToTail), latency|
		// need two calls because dictToNestedArgs is recursive
		^super.newCopyArgs(source, this.dictToNestedArgs(this.flatArgsToDict(args)), target, addAction).play(latency);
	}

	*basicNewByArgPaths { |source, args, target(Server.default.defaultGroup), addAction(\addToTail)|
		^super.newCopyArgs(source, this.dictToNestedArgs(this.flatArgsToDict(args)), target, addAction);
	}

	*flatArgsToDict { |argList|
		var dict = IdentityDictionary.new;
		argList.pairsDo { |path, item|
			var here = dict, plugArgs, parent;
			var separatedPath = path.asString.split($/);
			separatedPath.do { |name|
				var subdict;
				name = name.asSymbol;
				subdict = here[name];
				if(subdict.isNil) {
					subdict = IdentityDictionary.new;
					here[name] = subdict;
				};
				parent = here;
				here = subdict;
			};
			if(item.isKindOf(Plug)) {
				if(item.args.size > 0) {
					plugArgs = IdentityDictionary.new;
					item.args.pairsDo { |key, value|
						plugArgs.put(key, IdentityDictionary.new(
							proto: IdentityDictionary[
								\value -> value
							]
						));
					};
					// overwrite with previously defined path args
					here = plugArgs.putAll(here);
				};
			};
			here.proto = IdentityDictionary[
				\value -> item
			];
			parent.put(separatedPath.last.asSymbol, here);
		};
		^dict.postln
	}

	*dictToNestedArgs { |argDict|
		var argList;
		argDict.keysValuesDo { |name, item|
			var thing = item.proto.tryPerform(\at, \value);
			if(thing.isKindOf(Plug)) {
				thing.args = this.dictToNestedArgs(item);
			};
			if(thing.notNil) {
				argList = argList.add(name).add(thing);
			};
		};
		^argList
	}

	play { |latency|
		var bundle = this.prepareToBundle;
		this.sendBundle(bundle, latency);
	}

	prepareToBundle { |bundle(OSCBundle.new)|
		if(antecedents.isNil) { antecedents = IdentitySet.new };

		if(useGroup) {
			group = Group.basicNew(target.server);  // note, children can get this from 'dest'
			bundle.add(group.newMsg(target, addAction));
		};

		// node = Synth.basicNew(defName, target.server);
		// bundle.add(node.newMsg(group, argList, \addToTail));
		// question for later: this is now basically just like Plug
		// so do we even need a top-level object?

		controls = IdentityDictionary.new;

		concreteArgs = args.asOSCPlugArray(this, this, bundle);

		// maybe need to refactor this
		// all types of sources should flatten to a 'defName'?
		node = source.preparePlugSource(this, bundle, concreteArgs);
		this.getSynthDesc(bundle);
		source.preparePlugBundle(
			this, bundle, concreteArgs.asOSCArgArray,
			*this.bundleTarget
		);

		^bundle
	}

	setSourceToBundle { |src, bundle(OSCBundle.new)|
		if(synthDesc.notNil and: { synthDesc.hasGate }) {
			bundle.add(node.releaseMsg)
		} {
			bundle.add(node.freeMsg)
		};
		node = src.preparePlugSource(this, bundle, concreteArgs);
		// ^^ so that the above call serves as the validation
		// if you pass in a wrong object, that will barf without breaking the Syn instance
		source = src;
		this.getSynthDesc(bundle);
		source.preparePlugBundle(
			// here, Plugs should already have been concrete-ized
			// so this should just write bus-mapping symbols into
			// the array and *not* re-prepare any synths
			this, bundle, concreteArgs.asOSCArgArray,
			*this.bundleTarget
		);
		^bundle
	}
	source_ { |src, latency|
		var bundle = OSCBundle.new;
		this.setSourceToBundle(src, bundle);
		this.sendBundle(bundle, latency)
	}

	sendBundle { |bundle, latency|
		bundle.sendOnTime(target.server, latency);
		this.registerNodes;
	}

	registerNodes {
		if(watcher.notNil) { watcher.free };
		watcher = OSCFunc({ |msg|
			// node may have changed, if you hot-swapped the source
			if(node.notNil and: { node.nodeID == msg[1] }) {
				this.free(nil, \nodeEnded);  // that simple?
			};
		}, '/n_end', target.server.addr, argTemplate: [node.nodeID])
		// needs to survive cmd-.
		.fix.oneShot;
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
			concreteArgs = concreteArgs.add(key).add(value);
		};
	}
	makeSetBundle { |selector(\set), bundle(OSCBundle.new) ... argList|
		var doMap = { |map, key, value|
			var i;
			map.keysValuesDo { |ctlname, set|
				set.do { |object|
					var old;
					old = object.argAt(ctlname);
					if(object !== this) {
						bundle.add(object.perform(selector, ctlname, value));
						// Plug 'set' gets updated in the plug object, not here
						// so I don't need an updateArgs
					} {
						bundle.add(node.perform(selector, ctlname, value));
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
					this.addControl(this, key.asString.split($/).first.asSymbol, Array.new);
					map = controls[key];
					if(map.notNil) {
						doMap.(map, key, value);
					};
				}
			} {
				doMapPlug.(key, value)
			};
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

	// return should be a concrete Plug
	setPlugToBundle { |key, plug, bundle(OSCBundle.new)|
		var out = plug.asPluggable(this, this, bundle);
		bundle.add(node.setMsg(key, out.asMap));
		^out
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
		nodes.add(node);
		^nodes
	}

	getSynthDesc { |bundle|
		var msg, io;
		case
		{ source.isSymbol or: { source.isString } } {
			synthDesc = SynthDescLib.at(source.asSymbol);
		}
		{ source.isFunction } {
			msg = bundle.preparationMessages.last;
			if(msg[0] == \d_recv) {
				synthDesc = SynthDesc.readFile(CollStream(msg[1])).choose;
			}
		};
	}
	controlNames {
		^if(synthDesc.notNil) { synthDesc.controlNames }
	}

	// name/subname --> a specific plug
	// name --> the Syn
	// * controls, dropped for now, too complicated and maybe not meaningful
	// *name/subname --> that plug and all children
	// *name --> everywhere
	addControl { |object, name, path|
		var key;
		var a;
		var addTo = { |dict, name, object|
			if(dict[name].isNil) {
				dict[name] = IdentitySet.new;
			};
			dict[name].add(object);
		};

		key = (path ++ [name]).join($/).asSymbol;
		if(controls[key].isNil) { controls[key] = IdentityDictionary.new };

		a = object.argAt(name);
		// have: this object, cname, path
		// want: child plug and mapped cname
		if(a.isKindOf(Plug)) {
			// function is only to support inline vars
			{ |child|  // this loop is always top-level
				var obj = child, obj2;
				var mapKey = name;
				var cnames;
				while {
					if(obj.map.notNil and: { obj.map[mapKey].notNil }) {
						mapKey = obj.map[mapKey];
					};  // else use old mapKey
					obj.controlNames.do { |cn|
						this.addControl(obj, cn, path ++ [name.asString]);
					};
					obj2 = obj.argAt(mapKey);
					obj2.isKindOf(Plug)
				} {
					obj = obj2;
				};

				cnames = obj.controlNames;
				if(cnames.isNil or: { cnames.includes(mapKey) }) {
					addTo.(controls[key], mapKey, obj);
				};
			}.value(a);
		} {
			addTo.(controls[key], name, object);
		};
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

	server { ^target.server }
	dest { ^this }
	bundleTarget { |predecessor, downstream|
		^case
		// esp. for adding Plugs by '.set'
		// predecessor would have been set as lastPlug but that may not be valid
		// but the plug does know its downstream
		{ downstream.notNil and: { downstream.node.notNil } } {
			[downstream.node, \addBefore]
		}
		{ predecessor.notNil } {
			[predecessor.node, \addAfter]  // predecessor can only be a Plug, single-node
		}
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
		this.freeToBundle.sendOnTime(this.server, latency);
		this.didFree(*why);
	}

	freeToBundle { |bundle(OSCBundle.new)|
		if(group.notNil) {
			bundle.add([11, group.nodeID])
		} {
			if(node.notNil) {
				bundle.add([error: -1])
				.add(node.freeMsg)
				.add([error: -2])
			}
		};
		group = node = nil;
		^bundle
	}

	release { |latency, gate = 0|
		this.releaseToBundle(nil, gate).sendOnTime(this.server, latency)
	}

	releaseToBundle { |bundle(OSCBundle.new), gate = 0|
		bundle = bundle.add([15, node.nodeID, \gate, gate]);
		^bundle
	}

	didFree { |... why|
		this.changed(\didFree, *why);
	}

	// assumes already freed
	// for NRT, this just cascades through the tree and deletes buses, nodes etc.
	cleanup {
		this.changed(\didFree, \cleanup);
	}
	// bit of a dodge perhaps but...
	cleanupToBundle { |bundle(OSCBundle.new)|
		^this.server.makeBundle(false, { this.cleanup }, bundle);
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
				~group = Group.basicNew(~server, ~group);
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
				~group = Group.basicNew(~server, ~group);
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
						syn.wrapAt(i).setToBundle(nil, *args),
						~latency
					);
				};
			});
			// monoSyn?
		}
	}
}

// need to track client Syns and Plugs using an auto-generated synthdef
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
