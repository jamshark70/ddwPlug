/* gplv3 hjh */

+ SequenceableCollection {
	asOSCPlugArray { |dest, downstream, bundle, controlDict|
		var array = Array(100);		// allocate a bunch of space
		var savePath = controlDict.proto[\path];
		var path = savePath ++ [""];
		// path.debug("seqcoll:asOSCPlugArray");
		this.do { |e|
			if(e.isSymbol or: { e.isString }) {
				path.putLast(e.asSymbol);
			};
			controlDict.proto[\path] = path;
			// [e, path].debug("calling asOSCPlugEmbeddedArray");
			array = e.asOSCPlugEmbeddedArray(array, dest, downstream, bundle, controlDict);
		};
		controlDict.proto[\path] = savePath;
		^array
	}

	asOSCPlugEmbeddedArray { |array, dest, downstream, bundle, controlDict|
		array = array.add($[);
		this.do { | e | array = e.asOSCPlugEmbeddedArray(array, dest, downstream, bundle, controlDict) };
		^array.add($])
	}
}

+ Object {
	asPluggable { ^this.asControlInput }

	asOSCPlugEmbeddedArray { |array, dest, downstream, bundle, controlDict|
		^array.add(this.asPluggable(dest, downstream, bundle, controlDict))
	}
	asOSCPlugArray { |dest, downstream, bundle, controlDict|
		^this.asPluggable(dest, downstream, bundle, controlDict)
	}
	// asOSCPlugBundle { |dest| ^this.asPluggable(dest, controlDict) }

}

// do we really want to do this?
+ Function {
	asPluggable { |dest, downstream, bundle, controlDict|
		Error("Functions not supported in Syn yet").throw;
	}
}

// should not be polyphonic? NodeProxies don't auto-spawn
+ NodeProxy {
	asPluggable { |dest, downstream, controlDict|
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
	preparePlugBundle { |dest, bundle, args, controlDict, target, action|
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
	preparePlugBundle { |dest, bundle, args, controlDict, target, action|
		^this.asString.preparePlugBundle(dest, bundle, args, controlDict, target, action);
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
	preparePlugBundle { |dest, bundle, args, controlDict, target, action|
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
}
