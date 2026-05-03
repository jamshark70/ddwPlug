/* gplv3 hjh */

// like Synth but makes a bundle, including plug sources
// for ad hoc sources, requires some latency

// maybe MixedBundle, to handle prep messages
// in that case we need to pass the bundle down through the asOSCPlugArray chain

// also need to keep a dictionary of settable controls
// only non-plug controls

Syn : AbstractPatchableNode {
	// make sure to set rate and numChannels -- findOutputChannel should do

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
		antecedents = IdentitySet.new;
		descendants = IdentitySet.new;
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
		if(useGroup) {
			group = Group.basicNew(target.server);  // note, children can get this from 'dest'
			bundle.add(group.newMsg(target, addAction));
		};

		// array wrapper is for consistency with 'preparePlug...' methods
		concreteArgs = args.asOSCPlugArray(this.dest, this, bundle);

		this.prepareSource(bundle);

		source.preparePlugBundle(
			this, bundle, concreteArgs.asOSCArgArray,
			*this.bundleTarget(nil, bundle)
		);

		^bundle
	}

	// set-source is mostly implemented in AbstractPatchableNode
	setSourceTarget {
		^[node, \addBefore]
	}

	sendBundle { |bundle, latency|
		bundle.sendOnTime(target.server, latency);
		this.registerNodes;
	}

	// note, this should *not* be in Plug as well
	// if a Plug node dies prematurely, the bus remains active
	// (holding its prior value if it's a control bus)
	// this is least-intrusive, least-surprise behavior
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

	// Plug and Syn have a slight difference here
	appendConcreteArg { |key, value|
		concreteArgs = concreteArgs.add(key).add(value);
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

	isPlaying { ^node.notNil }
	server { ^target.server }
	asNodeID { ^node.nodeID }
	dest { ^this }

	// cases:
	// - useGroup == true: Always tail of the local group
	// - initial creation: downstream exists but its node does not (fall back to bundle)
	// - source_ doesn't use this method at all
	// - set(..., Plug): Uses downstream
	bundleTarget { |downstream, bundle|
		var n;
		if(downstream.notNil) {
			if(downstream.node.isNil) {
				// if you .set() a nested Plug, the immediate downstream isn't there
				// so we keep looking down as many levels of descendants as needed
				n = downstream.searchDownstreamNode;
				if(n.notNil) {
					^[n, \addBefore]
				};
			} {
				^[downstream.node, \addBefore]
			};
		};
		^case
		{ group.notNil } {
			[group, \addToTail]
		}
		// I had been tracking this with a variable 'lastPlug'
		// for use during initial creation, when downstream nodes don't exist yet
		// but then realized, the last created node ID exists in the bundle
		{ bundle.notEmpty } {
			[bundle.messages.last[2], \addAfter]
		}
		// first node to be created (innermost Plug)
		// should be relative to the user's choice of target
		// (note, I can't move this method out of Syn because
		// of these variable references; Plug should not have them)
		{
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
	// a Syn by definition has no descendants
	removeDescendant {}

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
