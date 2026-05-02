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

	// Plug, at *new time, only sets its parameters
	// see asPluggable below for the rest of the process
	init { |aSource, aArgs, aRate, aNumChannels, aMap, aConcrete = false|
		source = aSource;
		args = aArgs;
		rate = aRate;
		numChannels = aNumChannels;
		map = aMap;
		isConcrete = aConcrete ?? { false };
		controls = IdentityDictionary.new;
		antecedents = IdentitySet.new;
		descendants = IdentitySet.new;
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
		^bundle
	}

	asPluggable { |argDest, downstream, bundle|
		if(isConcrete) {
			dest = argDest;
			descendants.add(downstream);
			downstream.antecedents.add(this);
			if(bus.isNil) {
				// array wrapper is for consistency with 'preparePlug...' methods
				concreteArgs = args.asOSCPlugArray(this.dest, this, bundle);
				this.prepareSource(bundle);
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

	setSourceTarget {
		^if(predecessor.notNil) {
			// must be a Plug, only one node
			[predecessor.node, \addAfter]
		} {
			// if this has no predecessor, then it's the first Plug
			// so the referent is the plug's old node
			[node, \addBefore]
		};
	}

	setPlugToBundle { |key, plug, bundle(OSCBundle.new)|
		var new = plug.asPluggable(dest, this, bundle);
		this.updateArgs([key, new]);
		bundle.add(node.setMsg(key, new.asMap));
		^new
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
		// ('shared' happens to set the concrete flag)
		^this.class.shared(source, args, rate, numChannels, map)
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
