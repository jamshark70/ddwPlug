Pmsyn : FilterPattern {
	embedInStream { |inevent|
		var syns, ids, server, synCount;
		var stream = pattern.asStream;
		var event;
		var desc, lib, msgFunc; //, argNameSet;
		var mergeArgs = { |set, array|
			set = set.copy;
			array.do { |argname| set.add(argname.asSymbol) };
			set.as(Array)
		};

		while {
			event = stream.next(inevent);
			event.notNil
		} {
			switch(event[\type])
			{ \monoNote } {
				// note onset, need to pre-integrate arg list
				// It is possible for the synthdef to change,
				// so we can't collect them once outside the loop
				// no good interface to get synthdesc...
				lib = event[\synthLib] ?? { SynthDescLib.global };
				desc = lib[event[\instrument].asSymbol];
				if(desc.notNil) {
					msgFunc = desc.msgFunc; //.def.argNames.as(IdentitySet);
				};
				event.put(\type, \monoSyn)
				// .put(\args, mergeArgs.(argNameSet, event[\args]));
				.put(\argPairs, msgFunc.valueWithEnvir(event));
				inevent = event.yield;  // play it
				syns = event[\syn];  // last played event
				ids = event[\id];
				server = event[\server];
			}
			{ \monoSet } {
				event.put(\type, \synSet)
				.put(\syn, syns)
				.put(\id, ids)
				.put(\server, server)
				// .put(\args, mergeArgs.(argNameSet, event[\args]));
				.put(\argPairs, msgFunc.valueWithEnvir(event));
				inevent = event.yield;
			}
			{ inevent = event.yield };  // passthru
		}
	}
}
