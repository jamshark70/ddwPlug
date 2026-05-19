/* gplv3 hjh */

/*
Intended flow:
- Syn is the driver.
- When a Syn releases (n_end), it broadcasts didFree.
- Dependants should include:
- All Plug subclasses.
- The AutoReleaseBus? (Yes. Plugs may in theory end early.)
Plug:asPluggable takes care of this.
- So the \didFree notification should force-kill all the Plugs,
and remove itself from the AutoReleaseBus, at which point, the bus goes away too.
*/

AutoReleaseBus : Bus {
	var clients;

	free { |... why|
		if(index.notNil) { super.free };
		this.changed(\didFree, *why);
	}

	addClient { |client|
		if(clients.isNil) { clients = IdentitySet.new };
		clients.add(client)
	}

	removeClient { |client|
		clients.remove(client);
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

// need to track client Syns and Plugs using an auto-generated synthdef
SynthDefTracker {
	// new Dict: server -> function -> (synthdef: def, clients: IdentitySet)
	classvar <all;
	classvar <>timeout = 3;

	*initClass {
		all = IdentityDictionary.new;
	}

	// 'object' should be a Node
	*cache { |object, function|
		var coll = this.findCollFor(object, function);
		^if(coll.notNil) {
			coll
		}  // else nil
	}

	*register { |object, synthdef, function|
		var server = object.server;
		var coll = all[server];
		var func;
		if(coll.isNil) {
			coll = IdentityDictionary.new;
			all[server] = coll;
		};
		func = this.findEquivFunction(coll, function);
		if(func.isNil) {
			coll = IdentityDictionary[
				\synthdef -> synthdef,
				\synthdesc -> synthdef.asSynthDesc,
				\lastChange -> 0.0,
				\clients -> IdentitySet.new,
				\timeAdded -> SystemClock.seconds
			];
			all[server][function] = coll;
		} {
			coll = all[server][func];
		};
		coll[\clients].add(object);
		coll[\lastChange] = SystemClock.seconds;
	}

	*registerOutSpec { |object, function, rate, numChannels|
		var coll = this.findCollFor(object, function);
		if(coll.isNil) {
			"Can't register output spec for non-registered function".warn;
		} {
			coll[\rate] = rate;
			coll[\numCh] = numChannels;
		}
	}

	*release { |object, defname, function|
		var coll = all[object.server], func;
		var set;
		var releaseTime;
		if(coll.notNil) {
			func = this.findEquivFunction(coll, function);
			if(func.notNil) {
				coll = coll[func];
				set = coll[\clients];
				set.remove(object);
				if(set.isEmpty) {
					releaseTime = SystemClock.seconds;
					SystemClock.sched(timeout, {
						// if true, it means no nodes were registered for this synthdef
						// since 'releaseTime' and we go ahead and delete the def
						// if false, it means this def has been used in the interim
						// so, invalidate this check and wait for the last-last check
						if(coll[\lastChange] <= releaseTime) {
							object.server.sendMsg(\d_free, coll[\synthdef].name.asSymbol);
							all[object.server].removeAt(func);
						};
					});
				};
			};
		};
	}

	// O(n) but what choice have I got? hash isn't useful here
	// btw this is subtle:
	// consider \xxxPlug, { |xxx| Plug { ... } }
	// if the inner func directly uses xxx, then its 'context'
	// will be unique per invocation, and the 'compareObject' will fail
	// but if it's { |xxx| Plug({ |xxx| ... }, [xxx: xxx]) } as recommended,
	// the inner func's 'context' will be shared and caching should work
	*findEquivFunction { |coll, function|
		if(coll.notNil) {
			coll.keysDo { |func|
				if(function.compareObject(func)) {
					^func
				}
			};
			^nil
		};
		^nil
	}

	*findCollFor { |object, function|
		var coll = all[object.server];
		var key;
		^if(coll.notNil) {
			key = this.findEquivFunction(coll, function);
			if(key.notNil) { ^coll[key] }
			// else nil
		}  // else nil
	}
}

// takes over some of the scanMaps logic
ControlNameMap {
	var <maps;  // flat dictionary of paths relative to 'this' node

	*new { |maps(IdentityDictionary.new)|
		^super.newCopyArgs(maps)
	}

	// when the parent receives a ControlNameMap object,
	// it should prefix everything with the name of the Plug it came from
	// the Plug doesn't know, but the parent does
	// this is the real reason for this class
	prepend { |name|
		var newMap;
		newMap = maps.class.new;
		maps.keysValuesDo { |key, value|
			newMap.put((name ++ "/" ++ key).asSymbol,
				value.collect { |x| (name ++ "/" ++ x).asSymbol }
			)
		};
		^this.class.new(newMap)
	}

	followControlPathLinks { |key, result(IdentitySet.new)|
		// not-found entries are assumed to map to their own location
		if(maps[key].isNil) {
			result.add(key)
		} {
			maps[key].do { |linkedKey|
				if(linkedKey == key) {
					result.add(key)
				} {
					this.followControlPathLinks(linkedKey, result)
				}
			}
		};
		^if(result.isEmpty) { key } { result }
	}

	addAt { |name, object, dict, class(IdentitySet)|
		maps.addAt(name, object, class)
	}
	merge { |aControlMap|
		maps.putAll(aControlMap.maps)
	}
}
