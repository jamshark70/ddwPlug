/* gplv3 hjh */

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
	var <node;
	var <bus;  // use AutoReleaseBus
	// var nodes;
	var isConcrete = false;

	// Cable also needs to be told the destination
	// I think this is asPluggable?

	*new { |source, args, rate = \control, numChannels = 1|
		^super.newCopyArgs(source, args, rate, numChannels).init
	}

	init {
		// nodes = IdentitySet.new;
	}

	free {
		bus.removeClient(this);
		// nodes.do { |node| node.removeDependant(this) };
	}

	asPluggable { |dest, bundle, controlDict|
		if(isConcrete) {
			bus = AutoReleaseBus.perform(rate, dest.server, numChannels);

			// preparation messages, bundle messages
			node = source.preparePlugBundle(
				dest,
				bundle,
				args.asOSCPlugArray(dest, bundle, controlDict) ++ [out: bus],
				controlDict
			);

			// if(nodes.includes(dest).not) {
			// 	nodes.add(dest);
			// };
			bus.addClient(this);
			// it's a Set so multiple adds are OK (no redundancy)
			bus.addClient(dest);
			dest.addDependant(this);
			dest.addDependant(bus);
			^bus.asMap  // also need multichannel
		} {
			^this.concreteInstance.asPluggable(dest, bundle, controlDict)
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
			obj.removeDependant(this);
			node.server.sendBundle(nil,
				[\error, -1],
				node.freeMsg,
				[\error, -2]
			);
			bus.removeClient(this);
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
	var <>defName, <>args, <>target, <>addAction;

	// unlike Synth, does *not* send
	// you need to 'play' it
	// this way, one template can play multiple complexes
	*new { |defName, args, target(Server.default.defaultGroup), addAction = \addToHead|
		^super.newCopyArgs(defName, args, target, addAction)
	}

	play { |argTarget, argAddAction, argArgs, latency|
		^SynPlayer(
			defName,
			argArgs ?? { args },  // merge?
			argTarget ?? { target },
			argAddAction ?? { addAction },
			latency ?? { target.server.latency }
		)
	}
}

SynPlayer /*: Synth*/ {
	// NOPE defName, group, isPlaying, isRunning, nodeID, server

	var <>defName, <>args, <>target, <>addAction;
	var <node, <group, watcher;
	var <controls;  // flat dictionary of ctl paths --> node arrays

	*new { |defName, args, target, addAction, latency|
		^super.newCopyArgs(defName, args, target, addAction).init(latency);
	}

	init { |latency|
		var argList;
		var bundle = OSCBundle.new;

		group = Group.basicNew(target.server);  // note, children can get this from 'dest'
		bundle.add(group.newMsg(target, addAction));

		// this should exist before asOSCPlugArray
		node = Synth.basicNew(defName, target.server);

		controls = IdentityDictionary.new;
		argList = args.asOSCPlugArray(this, bundle, controls);

		bundle.add(node.newMsg(group, argList, \addToTail));
		bundle.sendOnTime(target.server, latency ?? { target.server.latency });

		watcher = OSCFunc({
			this.free(\nodeEnded);  // that simple?
		}, '/n_end', target.server.addr, argTemplate: [node.nodeID]).oneShot;
	}

	server { ^target.server }

	free { |... why|
		group.free;
		watcher.free;
		this.didFree(*why);
	}

	release {
		group.set(\gate, 0);  // let the watcher handle the rest
	}

	didFree { |... why|
		this.changed(\didFree, *why);
	}
}
