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
	preparePlugSource { |dest, bundle, argList|
		^Array.fill(argList.size, { Synth.basicNew(this, dest.server) });
	}
	preparePlugBundle { |dest, bundle, args, controlDict|
		args.do { |argList, i|
			bundle.add(dest.nodeAt(i).newMsg(dest.group, argList, \addToTail));
		};
		^dest.node
	}
}

+ Symbol {
	preparePlugSource { |dest, bundle, argList|
		^this.asString.preparePlugSource(dest, bundle, argList);
	}
	preparePlugBundle { |dest, bundle, args, controlDict|
		^this.asString.preparePlugBundle(dest, bundle, args, controlDict);
	}
}

+ Function {
	preparePlugSource { |dest, bundle, argList|
		var fadeTime = if(dest.rate == \audio, 0.1, nil);
		var def = this.asSynthDef(fadeTime: fadeTime, /*name: */);
		var node;
		bundle.addPrepare([\d_recv, def.asBytes]);
		^Array.fill(argList.size, { Synth.basicNew(def.name, dest.server) });
	}
	preparePlugBundle { |dest, bundle, args, controlDict|
		var nodes = IdentitySet.new;
		args.do { |argList, i|
			nodes.add(dest.nodeAt(i).nodeID);
			bundle.add(dest.nodeAt(i).newMsg(
				dest.group,
				argList,
				\addToTail
			));
		};
		dest.node.do { |node|
			OSCFunc({ |msg|
				nodes.remove(msg[1]);
				if(nodes.isEmpty) {
					dest.server.sendMsg(\d_free, node.defName);
				};
			}, '/n_end', dest.server.addr, argTemplate: [node.nodeID])
			.oneShot;
		};
		^dest.node
	}
}
