/* gplv3 hjh */

+ SequenceableCollection {
	asOSCPlugArray { |dest, downstream, bundle|
		var array = Array(100);		// allocate a bunch of space
		this.do { |e|
			array = e.asOSCPlugEmbeddedArray(array, dest, downstream, bundle);
		};
		^array
	}

	asOSCPlugEmbeddedArray { |array, dest, downstream, bundle|
		array = array.add($[);
		this.do { | e | array = e.asOSCPlugEmbeddedArray(array, dest, downstream, bundle) };
		^array.add($])
	}
}

+ Object {
	asPluggable { ^this.asControlInput }

	asOSCPlugEmbeddedArray { |array, dest, downstream, bundle|
		^array.add(this.asPluggable(dest, downstream, bundle))
	}
	asOSCPlugArray { |dest, downstream, bundle|
		^this.asPluggable(dest, downstream, bundle)
	}
	// asOSCPlugBundle { |dest| ^this.asPluggable(dest) }
	canMakePlug { ^false }
}

// do we really want to do this?
+ Function {
	asPluggable { |dest, downstream, bundle|
		// e.g.
		// Syn(\something, [input: { |freq| Plug(\lfo, [basefreq: freq]) }])
		^this.valueEnvir.asPluggable(dest, downstream, bundle)
	}
}

+ Ref {
	asPluggable { |dest, downstream, bundle|
		^this.dereference.valueEnvir.asPluggable(dest, downstream, bundle)
	}
	canMakePlug { ^value.canMakePlug }
}

// should not be polyphonic? NodeProxies don't auto-spawn
+ NodeProxy {
	asPluggable { |dest, downstream|
	}
}


// make OSCBundle timing work properly
// MixedBundle almost gets it right, but uses
// Main.elapsedTime -- nope. Use logical time.

+ OSCBundle {
	sendOnTime { |server, delta|
		var callTime;
		if(delta.isNil) {
			this.doPrepare(server, {
				server.sendBundle(nil, *messages)
			})
		} {
			callTime = SystemClock.seconds;
			this.doPrepare(server, {
				server.sendBundle(
					delta + callTime - SystemClock.seconds,
					*messages
				);
			});
		}
	}
}



// sources
+ String {
	preparePlugSource { |dest, bundle, argList|
		^Array.fill(argList.size, { Synth.basicNew(this, dest.server) });
	}
	preparePlugBundle { |dest, bundle, args, target, action|
		args.do { |argList, i|
			bundle.add(dest.nodeAt(i).newMsg(target /*dest.group*/, argList, action /*\addToTail*/));
		};
		^dest.node
	}
}

+ Symbol {
	preparePlugSource { |dest, bundle, argList|
		^this.asString.preparePlugSource(dest, bundle, argList);
	}
	preparePlugBundle { |dest, bundle, args, target, action|
		^this.asString.preparePlugBundle(dest, bundle, args, target, action);
	}
}

+ Function {
	preparePlugSource { |dest, bundle, argList|
		var fadeTime = if(dest.rate == \audio, 0.1, nil);
		var def = this.asSynthDef(fadeTime: fadeTime, /*name: */);
		var node;
		bundle.addPrepare([\d_recv, def.asBytes]);
		SynthDefTracker.register(dest, def.name);
		^Array.fill(argList.size, { Synth.basicNew(def.name, dest.server) });
	}
	preparePlugBundle { |dest, bundle, args, target, action|
		var nodes = IdentitySet.new;
		args.do { |argList, i|
			nodes.add(dest.nodeAt(i).nodeID);
			bundle.add(dest.nodeAt(i).newMsg(
				target /*dest.group*/,
				argList,
				action /*\addToTail*/
			));
		};
		dest.node.do { |node|
			OSCFunc({ |msg|
				nodes.remove(msg[1]);
				if(nodes.isEmpty) {
					SynthDefTracker.release(dest, node.defName);
				};
			}, '/n_end', dest.server.addr, argTemplate: [node.nodeID])
			.fix.oneShot;
		};
		^dest.node
	}
	canMakePlug { ^true }
}
