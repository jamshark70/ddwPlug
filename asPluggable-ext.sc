/* gplv3 hjh */

// remove nodeAt

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

	debug { |str|
		Post << str << ":\n";
		preparationMessages.do { |msg| "prep: %\n".postf(msg) };
		messages.do { |msg| "msg: %\n".postf(msg) };
	}
}



// sources
+ String {
	preparePlugSource { |dest, bundle, argList|
		^Synth.basicNew(this, dest.server)
	}
	preparePlugBundle { |dest, bundle, args, target, action|
		bundle.add(dest.node.newMsg(target /*dest.group*/, args, action /*\addToTail*/));
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
		var def;
		var node;
		// 'dest' is for the Server object
		def = SynthDefTracker.cache(dest, this);
		if(def.isNil) {
			def = this.asSynthDef(
				fadeTime: fadeTime,
				name: ("plugDef" ++ UniqueID.next).asSymbol
			);
			// only add prep message if it's a new synthdef
			bundle.addPrepare([\d_recv, def.asBytes]);
		} {
			if(SystemClock.seconds - def[\timeAdded] < 0.12) {
				bundle.addPrepare([\sync]);
			};
			def = def[\synthdef];
		};
		SynthDefTracker.register(dest, def, this);
		^Synth.basicNew(def.name, dest.server)
	}
	preparePlugBundle { |dest, bundle, args, target, action|
		var myID = dest.node.nodeID;
		var myDefName = dest.node.defName;
		bundle.add(dest.node.newMsg(
			target /*dest.group*/,
			args,
			action /*\addToTail*/
		));
		OSCFunc({ |msg|
			if(myID == msg[1]) {
				SynthDefTracker.release(dest, myDefName, this);
			};
		}, '/n_end', dest.server.addr, argTemplate: [dest.node.nodeID])
		.fix.oneShot;
		^dest.node
	}
	canMakePlug { ^true }
}
