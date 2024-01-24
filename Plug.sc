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
	var <antecedents, <descendants;
	var <isConcrete = false;

	// Cable also needs to be told the destination
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
		// dest is only the latest descendant
		// dest.removeDependant(this);
		descendants.do(_.removeDependant(this));  // not sure this is needed?
		this.changed(\didFree, \cableFreed);
		antecedents.do { |cable| cable.descendants.remove(this) };
		descendants.do { |cable| cable.antecedents.remove(this) };
		// nodes.do { |node| node.removeDependant(this) };
	}

	asPluggable { |argDest, downstream, bundle, controlDict|
		var argList;
		if(isConcrete) {
			dest = argDest;
			descendants.add(downstream);
			downstream.antecedents.add(this);
			if(bus.isNil) {
				bus = AutoReleaseBus.perform(rate, dest.server, numChannels);

				// only one synth per cable, for now
				argList = [
					args.asOSCPlugArray(dest, this, bundle, controlDict)
					++ [out: bus, i_out: bus]
				];
				// preparation messages, bundle messages
				node = source.preparePlugSource(this, bundle, argList)
				.at(0);  // one synth for now
				source.preparePlugBundle(
					this,
					bundle,
					argList,
					controlDict,
					*dest.bundleTarget
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
			dest.lastCable = this;
			^this.asMap;

		} {
			^this.concreteInstance.asPluggable(argDest, downstream, bundle, controlDict);
		}
	}

	nodeAt { |i| ^node }  // just the one
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
	// eventually drop 'group'
	var <node, <group, watcher, nodeIDs;
	var <antecedents;
	var <>lastCable;
	var <controls;  // flat dictionary of ctl paths --> node-or-cable arrays

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

		// group = Group.basicNew(target.server);  // note, children can get this from 'dest'
		// bundle.add(group.newMsg(target, addAction));

		// node = Synth.basicNew(defName, target.server);
		// bundle.add(node.newMsg(group, argList, \addToTail));
		// question for later: this is now basically just like Cable
		// so do we even need a top-level object?

		controls = IdentityDictionary.new;

		if(style == \event) {
			argList = this.multiChannelExpand(args);
		} {
			argList = [args];
		};
		argList = argList.collect { |a| a.asOSCPlugArray(this, this, bundle, controls) };

		// maybe need to refactor this
		// all types of sources should flatten to a 'defName'?
		node = source.preparePlugSource(this, bundle, argList);
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
					this.free(\nodeEnded);  // that simple?
				};
			}, '/n_end', target.server.addr, argTemplate: [n.nodeID])
			// needs to survive cmd-.
			.fix.oneShot;
		};
	}

	server { ^target.server }
	dest { ^this }
	nodeAt { |i| ^node[i] }
	bundleTarget {
		^if(lastCable.isNil) {
			[target, addAction]
		} {
			[lastCable.node, \addAfter]
		}
	}
	rate { ^\audio }

	free { |... why|
		// watcher.free;
		this.didFree(*why);
	}

	release {
		// group.set(\gate, 0);  // let the watcher handle the rest
		node.do(_.release);
	}

	didFree { |... why|
		this.changed(\didFree, *why);
	}

	multiChannelExpand { |args|
		// duplicate any non-shared cables, to preserve independence
		^this.duplicateCables(args).flop
	}

	duplicateCables { |args|
		^args.collect { |item|
			case
			{ item.isSequenceableCollection } {
				this.duplicateCables(item)
			}
			{ item.isKindOf(Cable) } {
				if(item.isConcrete) { item } { item.copy }
			}
			{ item }
		}
	}
}
