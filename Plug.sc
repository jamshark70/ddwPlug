/* gplv3 hjh */

// TODO: Is it safe to 'release' always?

/*
Intended flow:
- Syn is the driver.
- When a Syn releases (n_end), it broadcasts didFree.
- Dependants should include:
- All Cable subclasses.
- The AutoReleaseBus? (Yes. Cables may in theory end early.)
Cable:asPluggable takes care of this.
- So the \didFree notification should force-kill all the Cables,
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
		if(clients.notNil and: { clients.notEmpty }) {
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
\lfo, Cable { LFDNoise3.kr(0.1) }
)

You have one instance of Cable but you need one for each event.
So Cable will have to manufacture an instance of something.
Another class? Or 'copy' the cable and set a flag?
*/

Cable {
	var <source, <args, <rate, <numChannels;
	var <node, <dest;
	var <bus;  // use AutoReleaseBus
	// var nodes;
	var isConcrete = false;

	// Cable also needs to be told the destination
	// I think this is asPluggable?

	*new { |source, args, rate = \control, numChannels = 1|
		^super.newCopyArgs(source, args, rate, numChannels) // .init
	}

	*shared { |source, args, rate = \control, numChannels = 1|
		^this.new(source, args, rate, numChannels).prConcrete_(true)
	}

	// init {
	// 	// nodes = IdentitySet.new;
	// }

	free {
		if(node.notNil) {
			node.server.sendBundle(nil,
				[\error, -1],
				node.freeMsg,
				[\error, -2]
			);
			bus.removeClient(this);
			bus = node = nil;
		};
		this.changed(\didFree, \cableFreed);
		// nodes.do { |node| node.removeDependant(this) };
	}

	asPluggable { |argDest, bundle, controlDict|
		if(isConcrete) {
			dest = argDest;
			if(bus.isNil) {
				bus = AutoReleaseBus.perform(rate, dest.server, numChannels);

				// preparation messages, bundle messages
				node = source.preparePlugSource(this, bundle);
				node.defName.preparePlugBundle(
					this,
					bundle,
					args.asOSCPlugArray(dest, bundle, controlDict)
					++ [out: bus, i_out: bus],
					controlDict
				);
			};

			// if(nodes.includes(dest).not) {
			// 	nodes.add(dest);
			// };
			// these are all Sets so multiple adds are OK (no redundancy)
			bus.addClient(this);
			bus.addClient(dest);
			dest.addDependant(this);
			dest.addDependant(bus);
			^this.asMap
		} {
			^this.concreteInstance.asPluggable(argDest, bundle, controlDict)
		}
	}

	server { ^dest.server }
	group { ^dest.group }

	asMap {
		var rateLetter = bus.rate.asString.first;
		^if(bus.numChannels > 1) {
			Array.fill(bus.numChannels, { |i|
				(rateLetter ++ (bus.index + i)).asSymbol
			})
		} {
			bus.asMap
		}
	}

	concreteInstance {
		^this.class.new(source, args, rate, numChannels).prConcrete_(true)
	}

	prConcrete_ { |bool(false)|
		isConcrete = bool
	}

	update { |obj, what ... args|
		switch(what)
		{ \didFree } {
			this.free;
			obj.removeDependant(this);
		}
	}
}

// like Synth but makes a bundle, including plug sources
// for ad hoc sources, requires some latency

// maybe MixedBundle, to handle prep messages
// in that case we need to pass the bundle down through the asOSCPlugArray chain

// also need to keep a dictionary of settable controls
// only non-cable controls

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
}

SynPlayer /*: Synth*/ {
	// NOPE defName, group, isPlaying, isRunning, nodeID, server

	var <>source, <>args, <>target, <>addAction;
	var <node, <group, watcher;
	var <controls;  // flat dictionary of ctl paths --> node arrays

	*new { |source, args, target, addAction, latency|
		^super.newCopyArgs(source, args, target, addAction).init(latency);
	}

	init { |latency|
		var argList;
		var bundle = OSCBundle.new;

		group = Group.basicNew(target.server);  // note, children can get this from 'dest'
		bundle.add(group.newMsg(target, addAction));

		// node = Synth.basicNew(defName, target.server);
		// bundle.add(node.newMsg(group, argList, \addToTail));
		// question for later: this is now basically just like Cable
		// so do we even need a top-level object?
		node = source.preparePlugSource(this, bundle);

		controls = IdentityDictionary.new;
		argList = args.asOSCPlugArray(this, bundle, controls);

		// maybe need to refactor this
		// all types of sources should flatten to a 'defName'?
		node.defName.preparePlugBundle(this, bundle, argList, controls);

		bundle.sendOnTime(target.server, latency ?? { target.server.latency });

		watcher = OSCFunc({
			this.free(\nodeEnded);  // that simple?
		}, '/n_end', target.server.addr, argTemplate: [node.nodeID]).oneShot;
	}

	server { ^target.server }
	dest { ^this }

	free { |... why|
		group.free;
		// watcher.free;
		this.didFree(*why);
	}

	release {
		group.set(\gate, 0);  // let the watcher handle the rest
	}

	didFree { |... why|
		this.changed(\didFree, *why);
	}
}
