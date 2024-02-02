// plug tests

// OSCBundle

~debugAddr = DebugNetAddr("127.0.0.1", 57110);
~saveAddr = s.addr;
s.addr = ~debugAddr;
OSCFunc.trace(true, true);
s.volume = -20;

s.volume = 0;

(
SynthDef(\freqlfo, { |out, rate = 5, low = 200, high = 800|
	Out.kr(out, LFDNoise3.kr(rate).exprange(low, high));
}).add;
)

s.addr = ~saveAddr;

o = OSCBundle.new;
o.addPrepare([\d_recv, { (SinOsc.ar * 0.1).dup }.asSynthDef(name: \test1).asBytes]);
o.add(Synth.basicNew(\test1).newMsg);

o.dump
o.preparationMessages
o.messages

// should hit at the same time
(
o.sendOnTime(s, 0.2);
(sustain: 2).play;
)

[633.1015 + 0.2, 633.1287 + 0.1729]  // OK!



// simple Syn
x = Syn(\default, [freq: 400]);
y = x.play;

x.release;

z = x.play;
z.release;



// simple cable
x = Syn(\default, [freq: Plug(\freqlfo, [rate: 3])]);

x.release;

s.controlBusAllocator.debug  // all freed, good!


// nested cable
(
x = Syn(\default, [
	freq: Plug(
		\freqlfo,
		[rate: Plug(
			\freqlfo, [rate: 0.7, low: 1, high: 70]
		)]
	),
	amp: 0.05
]);
)

x.release;

x.concreteArgs
x.concreteArgs[0][1].concreteArgs

Object.dependantsDictionary.keysValuesDo { |key, value| [key, value].postln }; ""

x.controls.keysValuesDo { |k, v| [k, v].postln }; ""

// init
[class ProxyChain, IdentitySet[a Function]]
[class Server, IdentitySet[class MixerChannel]]
[OSCFunc(/mixerChBadValue, a NetAddr(127.0.0.1, 57110), nil, nil), IdentitySet[an OSCMessageDispatcher]]
[a Volume, IdentitySet[a SimpleController]]
[localhost, IdentitySet[a SimpleController]]
[OSCFunc(/status.reply, a NetAddr(127.0.0.1, 57110), nil, nil), IdentitySet[an OSCMessageDispatcher]]

// during
[class ProxyChain, IdentitySet[a Function]]
[class Server, IdentitySet[class MixerChannel]]
[OSCFunc(/mixerChBadValue, a NetAddr(127.0.0.1, 57110), nil, nil), IdentitySet[an OSCMessageDispatcher]]
[a Volume, IdentitySet[a SimpleController]]
[localhost, IdentitySet[a SimpleController]]
[OSCFunc(/status.reply, a NetAddr(127.0.0.1, 57110), nil, nil), IdentitySet[an OSCMessageDispatcher]]
** [OSCFunc(/n_end, a DebugNetAddr(127.0.0.1, 57110), nil, [1001]), IdentitySet[an OSCMessageDispatcher]]
** [a SynPlayer, IdentitySet[AutoReleaseBus(control, 0, 1, localhost), a Plug, a Plug, AutoReleaseBus(control, 1, 1, localhost)]]

// after -- back to original state, good
[class ProxyChain, IdentitySet[a Function]]
[class Server, IdentitySet[class MixerChannel]]
[OSCFunc(/mixerChBadValue, a NetAddr(127.0.0.1, 57110), nil, nil), IdentitySet[an OSCMessageDispatcher]]
[a Volume, IdentitySet[a SimpleController]]
[localhost, IdentitySet[a SimpleController]]
[OSCFunc(/status.reply, a NetAddr(127.0.0.1, 57110), nil, nil), IdentitySet[an OSCMessageDispatcher]]


// cable func
x = Syn(\default, [
	freq: Plug({ LFDNoise3.kr(8).exprange(200, 800) })
]);

x.release;


// shared cable
(
var cable;
x = Syn(\default, [
	freq: Plug({ LFDNoise3.kr(8).exprange(200, 800) }),
	pan: cable = Plug.shared({ SinOsc.kr(SinOsc.kr(0.2).exprange(0.5, 4)) }),
	amp: cable
]);
)

y.node.trace

x.release;

s.sendMsg(\n_trace, 1012);


// independent cables with ad-hoc Syn function
// note: should override rate based on function
// but we don't know the function's rate until resolving it

// note: Syn source function isn't analyzed for numChannels and rate
// bc the SynPlayer's output is the user's responsibility
// but in1 and in2 are

(
// note, doesn't need to be defined within the Syn
var freqPlug = Plug({ LFDNoise3.kr(8).exprange(200, 800) });

x = Syn(
	{ (NamedControl.ar(\in1, [0, 0]) + NamedControl.ar(\in2, [0, 0])) },
	[
		in1: Plug(\default, [freq: freqPlug, pan: -0.5]),
		in2: Plug(\default, [freq: freqPlug, pan: 0.5])
	]
);
)

y.nodeAt(0).trace

x.release;

x.argAt(\in1)

s.audioBusAllocator.debug


// multichannel expansion
(
x = Syn(\default, [
	freq: (3..5).collect { |i|
		Plug({ (1.1 ** LFDNoise3.kr(3)) * (200 * i) })
	},
	pan: #[-0.5, 0, 0.5].scramble
], style: \event);
)

s.sendMsg(\n_trace, 1001);

x.release;

x.concreteArgs

x.concreteArgs.flop

x.argAt(\freq)

// nested multichannel, this is nuts
(
x = Syn(\default, [
	freq: (3..5).collect { |i|
		Plug({ |factor, lfo| factor * lfo }, [
			factor: 200 * i,
			lfo: Plug({ 1.1 ** LFDNoise3.kr(3) })
		])
	},
	pan: #[-0.5, 0, 0.5].scramble
], style: \event);
)

x.release;

x.argAt(\freq)
x.argAt(\freq)[1].argAt(\lfo)


// note: cannot do math ops on a Plug
// maybe make it an AbstractFunction?
(
Event.addEventType(\syn, {|server|
	var freqs, lag, strum, sustain;
	var bndl, addAction, sendGate, ids, i;
	var msgFunc, instrumentName, offset, strumOffset, releaseOffset;

	// var schedBundleArray;

	freqs = ~freq /*~detunedFreq*/.value;

	// msgFunc gets the synth's control values from the Event
	msgFunc = ~getMsgFunc.valueEnvir;
	instrumentName = ~synthDefName.valueEnvir;

	// determine how to send those commands
	// sendGate == false turns off releases

	sendGate = ~sendGate ? ~hasGate;

	// update values in the Event that may be determined by functions

	~freq = freqs;
	~amp = ~amp.value;
	~sustain = sustain = ~sustain.value;
	lag = ~lag;
	offset = ~timingOffset;
	strum = ~strum;
	~server = server;
	~latency = ~latency ?? { server.latency };  // seriously...?
	~isPlaying = true;
	addAction = Node.actionNumberFor(~addAction);

	// compute the control values and generate OSC commands
	bndl = msgFunc.valueEnvir;

	~group = ~group.value;
	// why? because ~group is (by default) the defaultGroup's ID, not the object
	~group = Group.basicNew(~server, ~group);
	~syn = SynPlayer.basicNew(instrumentName, bndl, ~group, ~addAction);
	bndl = ~syn.prepareToBundle(\event);
	~syn.registerNodes;

	// schedule when the bundles are sent

	if (strum == 0) {
		{
			var start = thisThread.seconds;
			bndl.doPrepare(server, inEnvir {
				var latency;
				latency = ~latency + start - thisThread.seconds;
				~schedBundleArray.(lag, offset, server, bndl.messages, latency);
			});
		}.fork(SystemClock);
		if (sendGate) {
			~schedBundleArray.(
				lag,
				sustain + offset,
				server,
				[15 /* \n_set */, ~syn.node.collect(_.nodeID), \gate, 0].flop,
				~latency
			);
		}
	} {
		ids = ~syn.node.collect(_.nodeID);
		// I think I can't support this?
		// if (strum < 0) {
		// 	bndl = bndl.reverse;
		// 	ids = ids.reverse
		// };
		strumOffset = Array.series(~syn.node.size, offset, strum.abs);
		i = bndl.messages.size - ~syn.node.size;
		{
			var start = thisThread.seconds;
			bndl.doPrepare(server, inEnvir {
				var latency;
				latency = ~latency + start - thisThread.seconds;
				~schedBundleArray.(lag,
					Array.fill(i, offset) ++ strumOffset,
					server, bndl.messages, latency
				);
			});
		}.fork(SystemClock);
		if (sendGate) {
			if (~strumEndsTogether) {
				releaseOffset = sustain + offset
			} {
				releaseOffset = sustain + strumOffset
			};
			~schedBundleArray.(
				lag, releaseOffset, server,
				[15 /* \n_set */, ids, \gate, 0].flop,
				~latency
			);
		}
	}
});
)

e = (type: \syn, instrument: \default, freq: Plug({ LFTri.kr(0.1).range(200, 800) }), sustain: 2).play;

e[\syn].node
e[\syn].free;

e = (type: \syn, instrument: \default, freq: Plug({ LFDNoise3.kr(2).range(200, 800) }), pan: [-0.5, 0.5], sustain: 2).play;

// strum
// func has same hash, that's not good

e = (type: \syn, instrument: \default, strum: 0.15, freq: Array.fill(2, { |i| Plug({ |basefreq| basefreq + EnvGen.kr(Env.perc(0.01, 0.5), levelScale: 200) }, [basefreq: (i+1) * 300]) }), sustain: 2).play;


e = (type: \synOn, instrument: \default, freq: Plug({ LFDNoise3.kr(2).range(200, 800) }), pan: [-0.5, 0.5], sustain: 2).play;

e.putAll((type: \synSet, args: [\pan], pan: [0.5, -0.5])).play;

e.put(\type, \synOff).play;


// works, but no way to have two 'freq' entries
e = (type: \syn, instrument: \default, freqq: 440, freq: `{ |freqq| currentEnvironment.debug("in freq func"); Plug(\freqlfo, [rate: 3, low: freqq * 0.5, high: freqq * 2]) }, sustain: 2).play;

currentEnvironment.debug("in freq func");

e = (type: \syn, instrument: \default, freq: 440, freqPlug: { |freq| Plug(\freqlfo, [rate: 3, low: freq * 0.5, high: freq * 2]) }, sustain: 3).play;

(
p = Pbind(
	\type, \syn,
	\instrument, \default,
	\rate, Pexprand(2.0, 9.0, inf),
	\ratio, Pexprand(1.25, 3.5, inf),
	\freq, Pexprand(300, 700, inf),
	// has access to values in the event
	// and the function is evaluated last, so order doesn't really matter
	\freqPlug, { |freq, rate, ratio|
		Plug(\freqlfo, [rate: rate, low: freq / ratio, high: freq * ratio])
	},
	\dur, Pexprand(0.1, 1.5, inf),
	\legato, Pexprand(0.6, 3.0, inf),
	\pan, Pwhite(0.4, 0.8, inf) * Prand([-1, 1], inf),
).play;
)

p.stop;

s.controlBusAllocator.debug;

// order of exec
// nested cable
(
x = Syn(\default, [
	freq: Plug(
		\freqlfo,
		[rate: Plug(
			\freqlfo, [rate: 0.7, low: 1, high: 70]
		)]
	),
	amp: 0.05
]);

y = x.play;
)

y.release;

y.allNodes

s.controlBusAllocator.debug;

y.antecedents
y.antecedents.do { |c| c.dump }; ""

~freqPlug = y.antecedents.choose;  // only one
~freqPlug.antecedents.do { |c| c.dump }; ""

~ratePlug = ~freqPlug.antecedents.choose;
~ratePlug.antecedents  // empty

// ok weird thing on cmd-.
o = OSCFunc({ |msg| msg.postln }, '/test').fix.oneShot;
OSCFunc.allEnabled;

NetAddr.localAddr.sendMsg('/test');



// shared cable across Syns
(
var lfo = Plug.shared({ LFDNoise3.kr(2) });

l = lfo;

x = Syn(\default, [
	freq: Plug({ |lfo| lfo.linexp(-1, 1, 200, 800) }, [lfo: lfo])
], addAction: \addToTail).play;
y = Syn(\default, [
	freq: Plug({ |lfo| lfo.linexp(-1, 1, 1000, 3000) }, [lfo: lfo])
], addAction: \addToTail).play;
)

l.descendants.do(_.dump);

x.antecedents.do(_.dump);
y.antecedents.do(_.dump);

x.antecedents.choose.antecedents

[l, x.antecedents.choose, y.antecedents.choose]

l.dump

// now stop one
y.release;
x.release;


	changed { |... args|
		args.debug("% changed".format(this));
		this.dependants.debug;
		^super.changed(*args)
	}



// can we get numChannels from synthdef
x = { SinOsc.ar(440) }.asSynthDef(fadeTime: 0.1);
y = x.asSynthDesc;
y.dump
y.outputs
y.outputs[0].dump

b = x.asBytes;
d = SynthDesc.readFile(CollStream(b));
d = d.choose;

d.outputs.do(_.dump); ""


[].join($/).asCompileString
#[abc, def].join($/).asCompileString

SynthDescLib.at(\default).controls


y.controls.keysValuesDo { |k, v| [k, v].postln }; ""



// move

g = Group.basicNew(s, 500);
h = Group.basicNew(s, 501);

s.sendBundle(nil, g.newMsg, h.newMsg);

(
x = Syn(\default, [
	freq: Plug(
		\freqlfo,
		[rate: Plug(
			\freqlfo, [rate: 0.7, low: 1, high: 70]
		)]
	),
	amp: 0.05
]);

y = x.play;
)

y.moveAfter(h);  // 501 1001 1002 1003 500 ok!
y.moveToTail(s.defaultGroup);  // ok!

y.moveBefore(g);
y.moveToHead(h);

y.release;

// allNodes should test shared




// get
x = Synth(\default);

x.get(\flippydoo, _.postln);  // 0

x.free;

"freq".split($/).postcs

// wait up, 'controls' is wrong
[*freq/rate, IdentityDictionary[(out -> Plug[4042D8F3]), (rate -> Plug[CC3F78F9]), (high -> Plug[4042D8F3]), (low -> Plug[4042D8F3])]]

// nested test has 'rate' at 2 levels:
// freq/rate and freq/rate/rate
// *freq/rate --> ??
// *rate --> ??

[\selector] ++ nil ++ [123]

[123].grow(2)


(
y = Syn(\default, [
	freq: Plug(
		\freqlfo,
		[rate: Plug(
			\freqlfo, [rate: 0.7, low: 1, high: 70]
		)]
	),
	amp: 0.05
]);
)

y.set(\freq, 100);

y.set('freq/rate/rate', 2, 'freq/rate/high', 10);
y.set('freq/rate', 5, 'freq/high', 1000);

y.controls.keysValuesDo { |k, v| [k, v].postln }; ""

y.release;

(
y = Syn(\default, [
	freq: Plug(\freqlfo, [rate: 1.7]),
	amp: 0.05
]);
)

// no effect because main synth has no 'rate' input
// (sends message, but it fails)
y.set(\rate, 4);

// yes, operates on the Plug
y.set('freq/rate', 22);

y.controls['*rate']

// this breaks the link though
y.set('*freq/rate', 1.5);

y.release;


v = Voicer(1, Syn(\default), [pan: Plug({ LFDNoise3.kr(1) })]);

v.trigger1(440);
v.release(440);

v.trigger1(440);
v.trigger1(660);
v.release(660);

v.nodes[0].initArgs

s.sendMsg(\n_trace, 1000)



// slurs?
(
(
keys: #[master, rvbmc],
initLevel: -12.dbamp,
rvbLevel: -6.dbamp,
make: { |name|
	var out;
	~target = MixerChannel(name, s, 2, 2, ~initLevel, outbus: ~master, completionFunc: { |chan|
		chan.newPostSend(~rvbmc, ~rvbLevel);
	});

	out = Voicer(10,
		Syn.basicNew(\anaspl),
		[
			pmix: 0.8,
			pwidth: Plug({ SinOsc.kr(2.5) * 0.45 + 0.5 })
		],
		target: ~target
	);
	out.mapGlobal(\ffreq, nil, 2500, \freq);
	out
},
free: { ~target.free },
type: \vc) => Fact(\testVC);
)

BP(\t).free; VC(\t).free;
/make(testVC:t/melBP:t);
/t =>.0 t;

/t = "[****]::\ser*3..5("12156,")::\artic("\seq("~\seq*1..3("_")")")";

/t = "[1,~1''~1~1']";
/t(set(\freqPlug, { |freq| Plug({ |freq| freq * LFDNoise3.kr(2.5).exprange(0.7, 1.4) }, [freq: freq]) }));

/t = "[****]::\ser*3..5("12156,")";

/t(set(\freqPlug, Pif(Punop('coin', 0.15), { |freq| Plug({ |freq| freq * EnvGen.kr(Env([3, 1], [0.08], \exp)) }, [freq: freq]) }, 0))));

/t = "\ins("*", 5..10, 0.25)::\ser*3..7("10785'26")::\artic("~__")";

/t = "\ins("*", 5..10, 0.25)::\ser*3..11("10785'9'6'3'526")::\artic("\wrand("~_.>~", 3, 2, 1, 1)")";

/t(set(\freqPlug, { |freq, acc| if(acc > 0) { Plug({ |freq, acc| freq * EnvGen.kr(Env([(acc*2)+1, 1], [0.08], \exp)) }, [freq: freq, acc: acc]) } { freq } }));

BP('t')[\ffxx].release; BP('t').ffxx = BP('t').asMixer.playfx { |outbus| var sig = In.ar(outbus, 2); sig = sig + (0.7 * CombC.ar(sig, 10, [0.75, 1.25] / Lag.kr(~tempoCtl.kr, 0.05), 6)) };

VC(\t).panic

s.controlBusAllocator.debug;

BusDict.controlObjects[s].keys.maxItem

// /t(set(\freqPlug, { |freq| Plug({ freq * (EnvGen.kr(Env.perc(0.1, 0.08)) * 0.4 + 1) }) }));
//
// /t(set(\ratio, Pexprand(1.1, 1.5, inf), \freqPlug, { |freq, ratio| Plug({ freq * (EnvGen.kr(Env.perc(0.005, 0.08)) * (ratio-1) + 1) }) }));

/t = "[****]::\ser*3..5("12156,")::\ins("@", 5..9, 0.25)::\pitch("@", "4567", 0, 3, ".")";

BP('t')[\ffxx].release; BP('t').ffxx = BP('t').asMixer.playfx { |outbus| var sig = In.ar(outbus, 2); sig = sig + (0.7 * CombC.ar(sig, 10, [1.25, 2.25] / Lag.kr(~tempoCtl.kr, 0.05), 6)) };

// preparing twice? why?

/t = "1";

/t+;
VC(\t).playingNodes.do(_.dump);
/t-;

/hh.(\hardhh);
/hhh = "....";

x.prArticulate1

BP(\t).event

VC(\t).nodes[0].initArgDict

/t/hhh-



// help examples
(
a = Syn(\default, [
	freq: Plug {
		LFSaw.kr(
			LFTri.kr(0.2).exprange(0.5, 12)
		).exprange(150, 950)
	}
]);
)

s.queryAllNodes;

NODE TREE Group 0
   1 group
      1000 971719531
      1001 default

a.release;


Syn.useGroup = true;


SynthDescLib.at(\default).controlNames

// new 'controls'
// simple cable
x = Syn(\default, [freq: Plug(\freqlfo, [rate: 3])]);

x = Syn(\default, [freq: Plug(\freqlfo, [rate: 3], map: (freq: \rate))]);

x.release;

x.controls.keysValuesDo { |k, v| [k, v].postln }; ""

x.set(\freq, 12);
x.set('freq/high', 6000);

x.setToBundle(nil, \freq, 12)

(
e = (type: \synOn, freq: 300, freqPlug: { |freq|
	Plug({ |freq, rate| freq * (1.5 ** LFDNoise3.kr(rate)) }, [freq: freq, rate: 3])
}).play;
)

e.syn[0].controls

e.syn[0].controls.keysValuesDo { |k, v| [k, v].postln }; ""

[out, IdentityDictionary[(out -> IdentitySet[a Syn])]]

// this one is wrong, shouldn't it be freq/i_out?
[freq/freq, IdentityDictionary[(rate -> IdentitySet[Plug[91D27655]]), (freq -> IdentitySet[Plug[91D27655]]), (i_out -> IdentitySet[Plug[91D27655]])]]
[amp, IdentityDictionary[(amp -> IdentitySet[a Syn])]]
[pan, IdentityDictionary[(pan -> IdentitySet[a Syn])]]
[gate, IdentityDictionary[(gate -> IdentitySet[a Syn])]]
[freq, IdentityDictionary[(freq -> IdentitySet[Plug[91D27655]])]]


e.putPairs([\type, \synSet, args: [\freq], freq: 1000]).play;

e.put(\type, \synOff).play;

e = (type: \synOn, freq: 300).play;
e.put(\type, \synSet, args: #[freq], freq: 400).play;


// 3 point modulation
(
SynthDef(\modLin, { |out, base, mod, depth|
	Out.kr(out, base + (depth * mod));
}).add;

SynthDef(\modExp, { |out, base, mod, depth|
	Out.kr(out, base * (depth ** mod));
}).add;

SynthDef(\lfTri, { |out, rate = 1|
	Out.kr(out, LFTri.kr(rate));
}).add;
)

(
x = Syn(\default, [
	freq: Plug(\modExp, [
		base: 400,
		mod: Plug(\lfTri),
		depth: 1.7
	])
]);
)

x.set("freq/base", 700);

x.release;



(
x = Syn(\default, [
	freq: Plug(\modExp, [
		base: 400,
		mod: Plug(\lfTri),
		depth: 1.7
	], map: (freq: \base))
]);
)

// affects base, does not break modulation!
x.set("freq", 700);

x.set("freq/depth", 1.05, "freq/mod/rate", 6);

p = Pbind(\type, \synSet, \syn, x, \args, [\freq], \degree, Pwhite(-7, 7, inf), \dur, 0.25).play;

p.stop; x.release;

p.stop;



(
p = Pbind(
	\type, \syn,
	\instrument, \default,
	\dur, Pwhite(1, 5, inf) * 0.25,
	\legato, 0.98,
	// frequency stuff
	\degree, Pwhite(-7, 7, inf),
	\freqPlug, { |freq|  // <-- base value gets patched here
		// lazy: I'll just use an ad-hoc function
		Plug({ |freq|
			(
				EnvGen.kr(Env([1, 1.06], [0.7], 4))
				**
				LFTri.kr(4)
			) * freq
		}, [freq: freq])
	}
).play;
)

p.stop;



(
p = Pbind(
	\type, \syn,
	\instrument, \default,
	\dur, Pwhite(1, 5, inf) * 0.25,
	\legato, 0.98,
	// frequency stuff
	\degree, Pwhite(-7, 7, inf),
	\freqPlug, { |freq|  // <-- base value gets patched here
		// lazy: I'll just use an ad-hoc function
		Plug({ |freq, depth|
			(depth ** LFTri.kr(4)) * freq
		}, [
			freq: freq,
			depth: Plug({ EnvGen.kr(Env([1, 1.06], [0.7], 4)) })
		])
	}
).play;
)

p.stop;



x = Syn(\default, [freq: rrand(300, 700)]);
x.release;

x = Syn(\default, [
	freq: Plug({
		LFDNoise0.kr(
			LFSaw.kr(0.2).exprange(2, 10)
		).exprange(200, 800)
	})
]);

x.release;



(
var modPlug = Plug({ LFDNoise3.kr(4) });

x = Syn(\default, [
	freq: (150 * [3, 4, 5]).collect { |freq|
		Plug({ |freq, mod, depth = 1.5|
			freq * (depth ** mod)
		}, [freq: freq, mod: modPlug])
	}
], style: \event);
)

x.release;


Syn.useGroup = true;

(
var modPlug = Plug.shared({ LFDNoise3.kr(4) });

x = Syn(\default, [
	freq: (150 * [3, 4, 5]).collect { |freq|
		Plug({ |freq, mod, depth = 1.5|
			freq * (depth ** mod)
		}, [freq: freq, mod: modPlug])
	}
], latency: nil, style: \event);
)

x.release;

x.allNodes