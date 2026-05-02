/* gplv3 hjh */

// like Synth but makes a bundle, including plug sources
// for ad hoc sources, requires some latency

// maybe MixedBundle, to handle prep messages
// in that case we need to pass the bundle down through the asOSCPlugArray chain

// also need to keep a dictionary of settable controls
// only non-plug controls

Syn : AbstractPatchableNode {
	// make sure to set rate and numChannels

	var <>target, <>addAction;

	*new { |source, args, target(Server.default.defaultGroup), addAction(\addToTail), latency|
		^super.new.init(source, args, target, addAction).play(latency);
	}

	*basicNew { |source, args, target(Server.default.defaultGroup), addAction(\addToTail)|
		^super.new.init(source, args, target, addAction)
	}

	*newByArgPaths { |source, args, target(Server.default.defaultGroup), addAction(\addToTail), latency|
		// need two calls because dictToNestedArgs is recursive
		^super.new.init(source, this.dictToNestedArgs(this.flatArgsToDict(args)), target, addAction).play(latency);
	}

	*basicNewByArgPaths { |source, args, target(Server.default.defaultGroup), addAction(\addToTail)|
		^super.new.init(source, this.dictToNestedArgs(this.flatArgsToDict(args)), target, addAction);
	}

	init { |aSource, aArgs, aTarget, aAddAction|
		source = aSource;
		args = aArgs;
		target = aTarget;
		addAction = aAddAction;
		controls = IdentityDictionary.new;
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
		^dict
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

		// array wrapper is for consistency with 'preparePlug...' methods
		concreteArgs = args.asOSCPlugArray(this.dest, this, bundle);

		this.prepareSource(bundle);

		source.preparePlugBundle(
			this, bundle, concreteArgs.asOSCArgArray,
			*this.bundleTarget
		);

		^bundle
	}

	// setSourceToBundle { |src, bundle(OSCBundle.new)|
	// 	if(synthDesc.notNil and: { synthDesc.hasGate }) {
	// 		bundle.add(node.releaseMsg)
	// 	} {
	// 		bundle.add(node.freeMsg)
	// 	};
	// 	node = src.preparePlugSource(this, bundle, concreteArgs);
	// 	// ^^ so that the above call serves as the validation
	// 	// if you pass in a wrong object, that will barf without breaking the Syn instance
	// 	source = src;
	// 	this.getSynthDesc(bundle);
	// 	source.preparePlugBundle(
	// 		// here, Plugs should already have been concrete-ized
	// 		// so this should just write bus-mapping symbols into
	// 		// the array and *not* re-prepare any synths
	// 		this, bundle, concreteArgs/*.asOSCArgArray*/,
	// 		*this.bundleTarget
	// 	);
	// 	^bundle
	// }
	setSourceTarget {
		^[node, \addBefore]
	}
	// source_ { |src, latency|
	// 	var bundle = OSCBundle.new;
	// 	this.setSourceToBundle(src, bundle);
	// 	this.sendBundle(bundle, latency)
	// }

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
	// object: Syn or Plug
	// name: the control name within this object
	// path: the arg path to the object (not including name)
	addControl { |object, name, path, prMaps|
		var key = (path ++ [name]).join($/).asSymbol;
		var mapKey;
		var a, childPath;
		// become aware of any name-change or sibling maps
		if(prMaps.isNil) {
			prMaps = this.scanMaps(object, name, path);
		};
		[object, name, path, prMaps].debug(">> addControl");

		// add the control itself
		// prMaps should already contain sibling relationships,
		// so we shouldn't need to do anything special here
		mapKey = prMaps.followControlPathLinks(key);
		mapKey.debug("followControlPathLinks result");
		controls.addAt(key, nil, IdentityDictionary);  // init tree branch if needed
		mapKey.do { |mpk|
			// mpk is a full path to the target arg
			// controls entries should key off of the arg name to set
			controls[key].addAt(mpk.asString.basename.asSymbol, this.objectForPath(mpk))
		};

		// walk the tree and cache setKey -> (controlName -> object) relationships
		a = object.argAt(key);
		if(a.isKindOf(Plug)) {
			// do all child control names
			childPath = path ++ [name];
			a.controlNames.do { |cn|
				this.addControl(a, cn, childPath, prMaps);
			};
		};
		[object, name, path].debug("<< addControl");
	}
	scanMaps { |object, name, path, prMaps(IdentityDictionary.new)|
		var a = object.argAt(name);
		var mapName = name;
		var childPath;
		var makeKey = { |path, name| (path ++ [name]).join($/).asSymbol };
		if(a.isKindOf(Plug)) {
			childPath = path ++ [name];
			// two relevant cases:
			// 1. a has a map matching 'name'
			// 2. a has a control matching 'name' (map should override this)
			case
			{ mapName = a.map.tryPerform(\at, name); mapName.notNil } {
				prMaps.addAt(makeKey.(path, name), makeKey.(childPath, mapName));
			}
			// chain without changing name
			{ a.controlNames.tryPerform(\includes, name) ?? { false } } {
				prMaps.addAt(makeKey.(path, name), makeKey.(childPath, name));
			};

			a.controlNames.do { |cn|
				// remember that cn is the name *within 'a'* to examine
				// a should **NOT** be the arg value that cn points to!
				this.scanMaps(a, cn, childPath, prMaps);
			};
		};

		// and sibling check
		object.concreteArgs.pairsDo { |name2, value|
			var mapAt, key, childPath;
			if(name2 != name and: {
				value.isKindOf(Plug) and: {
					// looking for a Plug whose map contains the parent-level param = name
					mapAt = value.map.tryPerform(\at, name);
					mapAt.notNil
				}
			}) {
				key = makeKey.(path, name);
				prMaps.addAt(key, key);
				childPath = path ++ [name2];
				prMaps.addAt(key, makeKey.(childPath, mapAt));
				this.scanMaps(value, mapAt, childPath, prMaps);
			};
		};
		^prMaps
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

	isPlaying { ^node.notNil }
	server { ^target.server }
	asNodeID { ^node.nodeID }
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
		if(node.notNil) {
			bundle = bundle.add([15, node.nodeID, \gate, gate]);
		};
		^bundle
	}

	releaseMsg { |releaseTime = 0|
		var bundle = OSCBundle.new;
		if(releaseTime.notNil) {
			if(releaseTime <= 0) {
				releaseTime = -1
			} {
				releaseTime = (releaseTime + 1).neg
			}
		} {
			releaseTime = 0;
		};
		bundle.add(#[error, -1]);
		this.releaseToBundle(bundle, releaseTime);
		bundle.add(#[error, -2]);
		^bundle
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

}
