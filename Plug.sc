/* gplv3 hjh */

// TODO: Is it safe to 'release' always?

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

Plug : AbstractPatchableNode {
	var <>map;
	var <dest;
	var <bus;  // use AutoReleaseBus
	var <predecessor;
	var <isConcrete = false;

	*new { |source, args, rate = \control, numChannels = 1, map|
		^super.new.init(source, args, rate, numChannels, map)
	}

	*shared { |source, args, rate = \control, numChannels = 1, map|
		^this.new.init(source, args, rate, numChannels, map, true)
	}

	init { |aSource, aArgs, aRate, aNumChannels, aMap, aConcrete = false|
		source = aSource;
		args = aArgs;
		rate = aRate;
		numChannels = aNumChannels;
		map = aMap;
		isConcrete = aConcrete ?? { false };
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
				this.findOutputChannel(bundle, source, node);
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
	asNodeID { ^node.nodeID }

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

	addControl {
	}

	scanMaps {
	}

	scanSiblingMaps {
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
