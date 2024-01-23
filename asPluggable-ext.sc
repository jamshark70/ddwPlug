/* gplv3 hjh */

+ SequenceableCollection {
	asOSCPlugArray { |dest, bundle, controlDict|
		var array = Array(100);		// allocate a bunch of space
		this.do { | e | array = e.asOSCPlugEmbeddedArray(array, dest, bundle, controlDict) };
		^array
	}

	asPlugEmbeddedArray { |array, dest, bundle, controlDict|
		array = array.add($[);
		this.do { | e | array = e.asOSCPlugEmbeddedArray(array, dest, bundle, controlDict) };
		^array.add($])
	}
}

+ Object {
	asPluggable { ^this.asControlInput }

	asOSCPlugEmbeddedArray { |array, dest, bundle, controlDict|
		^array.add(this.asPluggable(dest, bundle, controlDict))
	}
	asOSCPlugArray { |dest, bundle, controlDict|
		^this.asPluggable(dest, bundle, controlDict)
	}
	// asOSCPlugBundle { |dest| ^this.asPluggable(dest, controlDict) }

}

// do we really want to do this?
+ Function {
	asPluggable { |dest, bundle, controlDict|
		Error("Functions not supported in Syn yet").throw;
	}
}

// should not be polyphonic? NodeProxies don't auto-spawn
+ NodeProxy {
	asPluggable { |dest, controlDict|
	}
}


// make OSCBundle timing work properly
// MixedBundle almost gets it right, but uses
// Main.elapsedTime -- nope. Use logical time.

+ OSCBundle {
	sendOnTime { |server, delta|
		var callTime = SystemClock.seconds;
		this.doPrepare(server, {
			this.prSend(server,
				(delta ?? { server.latency }) + callTime - SystemClock.seconds,
				callTime
			);
		});
	}
}



// sources
+ String {
	preparePlugSource { |dest, bundle|
		^Synth.basicNew(this, dest.server);
	}
	preparePlugBundle { |dest, bundle, args, controlDict|
		bundle.add(dest.node.newMsg(dest.group, args, \addToTail));
		^dest.node
	}
}

+ Symbol {
	preparePlugSource { |dest, bundle|
		^this.asString.preparePlugSource(dest, bundle);
	}
	preparePlugBundle { |dest, bundle, args, controlDict|
		^this.asString.preparePlugBundle(dest, bundle, args, controlDict);
	}
}

+ Function {
	preparePlugSource { |dest, bundle|
		var def = this.asSynthDef(fadeTime: 0.1, /*name: */);
		var node;
		bundle.addPrepare([\d_recv, def.asBytes]);
		^Synth.basicNew(def.name, dest.server);
	}
	preparePlugBundle { |dest, bundle, args, controlDict|
		bundle.add(dest.node.newMsg(
			dest.group,
			args,
			\addToTail
		));
		OSCFunc({
			dest.server.sendMsg(\d_free, def.name);
		}, '/n_end', dest.server.addr, argTemplate: [dest.node.nodeID])
		.oneShot;
		^dest.node
	}
}
