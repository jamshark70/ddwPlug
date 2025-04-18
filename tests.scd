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

SynthDefTracker.all

(
f = {
	Syn(\default, [
		freq: Plug({ LFDNoise3.kr(8).exprange(200, 800) })
	]);
};
)

x = f.value;
x.release;

y = f.value;
y.release;

f = { |a = 1| { a } };

f = { |a = 1| { 123 } };

a = f.value(1);
b = f.value(2);

a == b
a.hash
b.hash
Function.findRespondingMethodFor(\hash)

a.instVarHash
b.instVarHash

a.dump
b.dump

a.compareObject(b)


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


// multichannel expansion -- cancelled
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
)

x.moveAfter(h);  // 501 1001 1002 1003 500 ok!
x.moveToTail(s.defaultGroup);  // ok!

x.moveBefore(g);
x.moveToHead(h);

x.release;

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

y.controls['freq/rate/rate'].keysValuesDo { |key, set| set.do { |plug| plug.node.postln } }; ""

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
	).debug("voicer");
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

SynthDefTracker.all



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


// no, cancelled
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


(
x = Syn(\default, [
	freq: Plug({ |freq|
		freq * (1.1 ** LFTri.kr(3))
	}, [freq: 400])
]);
)

x.argAtPath("freq");  // a Plug

x.argAtPath("freq").source = { |freq| freq * (2.2 ** LFSaw.kr(12)) };

// forwards to "freq/freq"
// because setting a number should not accidentally overwrite a Plug
x.set(\freq, 600);

// control 'freq/freq' itself by a new Plug (didn't exist at first)
x.set("freq/freq", Plug { LFTri.kr(0.2).exprange(200, 800) });

// now there are 3 synth nodes

// let's overwrite the 'freq' Plug with a different idea
x.set("freq", Plug { LFDNoise0.kr(LFDNoise3.kr(0.2).exprange(5, 18)).exprange(200, 800) });

// now there are 2 nodes:
// deleting the 'freq * ...' Plug also deleted its upstream

x.release;


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

x.argAtPath("in1/pan");
-> -0.5

x.release;



(
var modPlug = Plug.shared({ LFDNoise3.kr(4) });

x = (150 * [3, 4, 5]).collect { |freq|
	Syn(\default, [
		freq: Plug({ |freq, mod, depth = 1.5|
			freq * (depth ** mod)
		}, [freq: freq, mod: modPlug])
	])
};
)

x.do(_.release);


// benchmarking
(
var args = [freq: 440, amp: 0.1, pan: 0, ffreq: 2000, rq: 0.7];

a = Array.fill(10, {
	bench { 100000.do { Synth(\default, args) } }
});

[a.mean, a.median]
)

time to run: 0.579086725 seconds.
time to run: 0.54932334600001 seconds.
time to run: 0.550036088 seconds.
time to run: 0.54964802800001 seconds.
time to run: 0.54928513199999 seconds.
time to run: 0.55017210699999 seconds.
time to run: 0.547464076 seconds.
time to run: 0.54532069499999 seconds.
time to run: 0.55396658200002 seconds.
time to run: 0.55111055899999 seconds.
-> [0.5525413338, 0.549842058]


// the bottleneck is registerNodes
// benchmarking can't handle this well
// because normal use assumes that nodes will end
// at which point the node watchers are removed
// here they just accumulate
(
var args = [freq: 440, amp: 0.1, pan: 0, ffreq: 2000, rq: 0.7];

a = Array.geom(5, 1, 10).collect { |num|
	bench { num.do { Syn(\default, args) } }
};

// [a.mean, a.median]
)

time to run: 0.00037673000002769 seconds.
time to run: 0.0012627129999601 seconds.
time to run: 0.0058510110000043 seconds.
time to run: 0.160144753 seconds.
time to run: 13.804390943 seconds.
-> [0.00037673000002769, 0.0012627129999601, 0.0058510110000043, 0.160144753, 13.804390943]


// if I comment out the registerNodes call,
// bundle construction is roughly linear
(
var args = [freq: 440, amp: 0.1, pan: 0, ffreq: 2000, rq: 0.7];
var x;

a = Array.geom(5, 1, 10).collect { |num|
	bench { num.do {
		x = Syn.basicNew(\default, args);
		x.prepareToBundle(\synth);
	} }
};

// [a.mean, a.median]
)

time to run: 6.6754000044966e-05 seconds.
time to run: 0.00033927399999811 seconds.
time to run: 0.0036413149999817 seconds.
time to run: 0.013295040999992 seconds.
time to run: 0.11373358100002 seconds.
-> [6.6754000044966e-05, 0.00033927399999811, 0.0036413149999817, 0.013295040999992, 0.11373358100002]



// nrt?
(
var add = { |score, time, prep, msgs|
	prep.do { |msg| a.add([time, msg]) };
	msgs.do { |msg| a.add([time, msg]) };
};

z = Server(\nrt, options: ServerOptions.new.numOutputBusChannels_(2).sampleRate_(44100));

a = Score.new;

protect {
	a.add([0, ['/d_recv', SynthDescLib.at(\default).def.asBytes]]);

	x = Syn.basicNew(\default, [
		freq: Plug { XLine.kr(1000, 400, 0.1) }
	], z.defaultGroup);
	b = OSCBundle.new;
	x.prepareToBundle(b);
	add.(a, 0, b.preparationMessages, b.messages);

	y = Syn.basicNew(\default, [
		freq: Plug { XLine.kr(2000, 600, 0.1) }
	], z.defaultGroup);
	b = OSCBundle.new;
	y.prepareToBundle(b);
	add.(a, 1, b.preparationMessages, b.messages);

	b = List.new;
	x.releaseToBundle(b);
	add.(a, 2, b);

	// 0.3 sec later, synth is gone
	b = x.cleanupToBundle;
	add.(a, 2.3, b);

	b = List.new;
	y.releaseToBundle(b);
	add.(a, 3, b);

	// 0.3 sec later, synth is gone
	b = y.cleanupToBundle;
	add.(a, 3.3, b);
} { z.remove };
)

a.score.do(_.postln); ""

a.recordNRT(outputFilePath: "~/syn-nrt-test.wav".standardizePath, action: { "done".postln });


// access args
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

// ok, only args given at this level
x.argAt('freq')
x.slotAt(\argLookup)

x.argAt('freq').argAt('rate')
x.argAt('freq').concreteArgs
x.slotAt(\argLookup)

// more array stuff lurking here
x.set('freq/rate/rate', 3)

x.controls.keysValuesDo { |key, value| [key, value].postln }; ""

x.release;


// retest 'set' with xxxPlug
// multiple function layers is a bit awkward, add sugar later
e = (type: \synOn, freq: 300, freqPlug: { |freq| Plug({ |freq| freq * (1.1 ** LFTri.kr(3)) }, [freq: freq]) }).play;

e.putAll((type: \synSet, args: ['freq'], freq: 400)).play;

e.putAll((type: \synSet, args: ['freq'], freq: 500)).play;

e.put(\type, \synOff).play;

e[\syn][0].controls.keysValuesDo { |key, value| [key, value].postln }; ""

(
x = Syn(\default, [
	freq: Plug({ |freq|
		freq * (1.1 ** LFTri.kr(3))
	}, [freq: 400])
]);
)

x.set('freq', 500);

x.release;


(
x = Syn(\default, [
	freq: Plug({ |base|
		base * (1.1 ** LFTri.kr(3))
	}, [base: 400], map: (freq: \base))
]);
)

x.set('freq', 500);

x.argAtPath('freq/base')
x.argAtPath('freq/freq')

x.release;



// problem for 'set-to-plug': freq is already mapped to the sub-level plug
// also, if there's no freq/freq (or mapped), then there's no entry
// so the 'set' gets skipped totally



// also retest hot-swapping source
(
x = Syn(\default, [
	freq: Plug({ |freq|
		freq * (1.1 ** LFTri.kr(3))
	}, [freq: 400])
]);
)

// easier without the array nonsense
// need to reintroduce argAtPath
x.argAt(\freq).source = { |freq| freq * (2.2 ** LFSaw.kr(12)) };

x.argAtPath("freq");
x.argAtPath("freq/freq");
x.argAtPath("freq/freq/empty");

x.argAtPath("freq/freq");
x.set('freq', 500);
x.argAtPath("freq/freq");

x.argAtPath("amp")
x.set('amp', 0.05);
x.argAtPath("amp")

x.concreteArgs
x.args

x.release;



// set-to-plug
x = Syn(\default);

x.set(\freq, Plug { LFSaw.kr(12).exprange(200, 800) });

x.set(\freq, Plug { (LFSaw.kr(0.2, mul: 0.7) + LFPulse.kr(12, mul: 0.3)).exprange(800, 200) });

x.set(\amp, 0.5);

s.controlBusAllocator.debug;

x.set(\freq, Plug { |base = 400| (LFSaw.kr(0.2, mul: 0.7) + LFPulse.kr(12, mul: 0.3)).exprange(base * 2, base * 0.5) }, \amp, 0.5);

x.controls.keysValuesDo { |k, v| [k, v].postln }; ""

x.set('freq/base', 600);

x.argAt(\freq);

x.set('freq/base', Plug { |low = 300, high = 3000| LFDNoise3.kr(0.8).exprange(low, high) });

x.set('freq/base/low', 100, 'freq/base/high', 500);

x.objectForPath('freq/base').dump

x.free;

s.controlBusAllocator.debug;  // all stale bus refs go away! yes!




// get Voicer to *really* work
(
t = TimedBundle.new;

t.addPrepare([\d_recv, SynthDef(\test, { |out, gate = 1, freq = 440, amp = 0.1| Out.ar(out, (SinOsc.ar(freq) * (amp * EnvGen.kr(Env.asr, gate, doneAction: 2))).dup) }).asBytes]);
t.add([0, (x = Synth.basicNew(\test, s)).newMsg]);
t.add([2, x.setMsg(\gate, 0)]);
t.sendOnTime(s, 0.2);
)

s.sendBundle(nil, t.preparationMessages[0][1]);
(instrument: \test).play;


/make(fmMelVC:fmm/melBP:fmm);
/fmm =>.0 t;
/fmm = "[1357]";

/fmm+
/fmm-

/drum.(\tightsnr);
/tsn = "----";
/tsn+

/fmm(leadTime = 0.2)

/fmm/tsn-

/fmm = "[1357]::\xpose("(13)(14)(15)")";


// flat args
// a-ha... so it's not an MLID b/c "freq" isn't a leaf but needs an item
["freq", Plug { |rate = 1| ... }, "freq/rate", 3]
-->
ID[
	"freq" (path) -> ID[
		name: "freq",  // actually put these 2 in the proto
		value: Plug({ ... src ... }),
		args: ID[
			"freq/rate" -> ID[
				name: "rate",
				value: 3
			]
		]
	]
]

x = ["freq", Plug(\xyz), "freq/rate", 3];

(
// separatedPath = array of symbols
var putPath = { |dict, separatedPath, item|
	var here = dict, parent;
	separatedPath.do { |name|
		var subdict;
		name = name.asSymbol;
		subdict = here[name];
		if(subdict.isNil) {
			subdict = IdentityDictionary.new;
			here[name] = subdict;
		};
		parent = here;
		here = subdict;
	};
	if(here.proto.notNil) {
		Error("Duplicate value being put at " ++ separatedPath.join($/)).throw;
	};
	[separatedPath, here].debug("path, here");
	here.proto = IdentityDictionary[
		// \name -> separatedPath.last.asSymbol,
		\value -> item
	].debug("proto");
	parent.put(separatedPath.last.asSymbol/*.debug("here's location")*/, here);
	dict
};

f = { |argList|
	var dict = IdentityDictionary.new;
	argList.pairsDo { |path, item|
		putPath.(dict, path.asString.split($/), item);
	};
	dict
};
)

d = f.(x);

d.keysValuesDo { |k, v| [k, v].postln }; ""
d.proto

d[\freq].keysValuesDo { |k, v| [k, v].postln }; ""
d[\freq].proto
d[\freq][\rate].proto
d[\freq]['freq/rate'].proto

// now try to build nested arg list
(
g = { |argDict|
	var argList;
	argDict.keysValuesDo { |name, item|
		var thing = item.proto.tryPerform(\at, \value);
		if(thing.isKindOf(Plug)) {
			thing.args = g.(item);
		};
		if(thing.notNil) {
			argList = argList.add(name).add(thing);
		};
	};
	argList
};
)

a = g.(d);
a[1].args


(
x = Syn.newFlat(\default, [
	"freq", Plug({ |rate = 3| LFDNoise3.kr(rate).exprange(200, 800) }),
	"freq/rate", Plug({ SinOsc.kr(0.5).exprange(1, 25) })
]);
)

x.args
x.args[1].args
x.free;



(
SynthDef(\freqlfo, { |out, rate = 5, low = 200, high = 800|
	Out.kr(out, LFDNoise3.kr(rate).exprange(low, high));
}).add;
)

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

x.argAt(\freq)  // a Plug
x.argAt(\freq).concreteArgs  // rate --> another Plug
x.argAt(\freq).argAt(\rate).concreteArgs

x.free;

(
y = Syn.newByArgPaths(\default, [
	"freq": Plug(\freqlfo),
	"freq/rate": Plug(\freqlfo),
	"freq/rate/rate", 0.7,
	"freq/rate/low", 1,
	"freq/rate/high", 70,
	"amp", 0.05
]);
)

(
y = Syn.newByArgPaths(\default, [
	"freq": Plug(\freqlfo),
	"freq/rate": Plug(\freqlfo, [low: 1, high: 70]),
	"freq/rate/rate", 0.7,
	// "freq/rate/low", 1,
	// "freq/rate/high", 70,
	"amp", 0.05
]);
)

(
y = Syn.newByArgPaths(\default, [
	"freq/rate/rate", 0.7,
	"freq/rate/high", 6,
	"freq": Plug(\freqlfo),
	"freq/rate": Plug(\freqlfo, [low: 1, high: 70]),
	// "freq/rate/low", 1,
	// "freq/rate/high", 70,
	"amp", 0.05
]);
)

y.argAt(\freq)  // a Plug
y.argAt(\freq).concreteArgs  // rate --> another Plug
y.argAt(\freq).argAt(\rate).concreteArgs

y.free;



// Syn source_
a = Syn(\default, [freq: Plug(\freqlfo)]);

// prelim testing: only updates var, not server
a.source = { |freq = 440| VarSaw.ar(freq, 0, 0.8).dup * 0.1 };

/*
relevant code:
		node = source.preparePlugSource(this, bundle, concreteArgs);
		this.getSynthDesc(bundle);
		source.preparePlugBundle(
			this, bundle, concreteArgs.asOSCArgArray,
			*this.bundleTarget
		);
*/

b = OSCBundle.new;

n = a.source.preparePlugSource(a, b, a.concreteArgs);
a.slotPut(\node, n);

b.preparationMessages  // d_recv OK

a.getSynthDesc(b);

a.synthDesc  // i_out freq fadeTime gate OK

a.source.preparePlugBundle(a, b, a.concreteArgs.asOSCArgArray, *a.bundleTarget);

b.messages  // correct creation message

a.free;




// crossfade
(
a = Syn(\default, [
	freq: Plug({
		LFDNoise3.kr(2).exprange(600, 1000)
	}, [fadeTime: 5])
]);
)

(
a.argAtPath("freq").source = {
	LFSaw.kr(5).exprange(80, 500)
};
)

a.free;

(
a = Syn({ |ffreq = 2000|
	LPF.ar(\in.ar(0!2), ffreq) * 0.1
}, [in: Plug({ Saw.ar([440, 441]) }, [fadeTime: 5], rate: \audio)]);
)

a.argAtPath("in").source = { VarSaw.ar([220, 221], 0, 0.7) };

a.free;



// what about Plug-ging something that is bus-mapped?
(
v = Voicer(10, Syn.basicNew(\default));
v.mapGlobal(\pan, nil, 0, \bipolar);
)

v.gate(440, 1, 0.5);

Event.eventTypes[\voicerNote].postcs

v.unmapGlobal(\pan);

// 'args' in the event are passed through to triggerMsg
(
e = (
	type: \voicerNote, voicer: v,
	freq: 330
).play;
)

// to see 'panPlug', 'pan' needs to be included in the event
// but it's mapped globally
(
e = (
	type: \voicerNote, voicer: v,
	freq: 330,
	panPlug: { |pan| pan.postln; 0 }
).play;
)




// prefixed parameters?
(
SynthDef(\filter, { |out, ffreq = 1000, rq = 0.01|
	var in = NamedControl.ar(\in, 0!2);
	in = BLowPass4.ar(in, ffreq, rq);
	DetectSilence.ar(in, doneAction: 2);
	Out.ar(out, in);
}).add;
)

e = (type: \syn, instrument: \filter, in: Plug(\default), 'in/freq': 900).play;
e.put(\type, \synOff).play;

e = (type: \syn, instrument: \filter, in: Plug(\default, [freq: 900])).play;
e.put(\type, \synOff).play;



// pmono?
e = (type: \synOn, amp: Plug { SinOsc.kr(0.25) }).play;

e[\syn][0].node.nodeID

// not synOff -- ah, it *does* clear the whole thing
(type: \off, id: e[\syn].collect { |syn| syn.node.nodeID }).play;

// hm, how does 'envirPairs' handle nil?
~abc = 1;
[\abc, \def].envirPairs  // drops def! Great!

SynthDescLib.at(\default).msgFunc.def.argNames.envirPairs

// setToBundle is doing something weird
x = Syn(\default);
z = x.setToBundle(nil, \out, 0, \freq, 316.28480468049, \amp, 0.1, \pan, 0.0);
z.messages
x.free;

// did synSet never work?
e = (type: \synOn).play;
e.putAll((type: \synSet, args: #[freq], freq: 72.midicps)).play;
e.put(\type, \synOff).play;

p = Pmono(\default, \freq, Pseries(1, 1, inf) * 100, \dur, 1).collect { |ev| thisThread.clock.sched(0.1, { ev.postln }); ev }.play;
p.stop;

p = Pmsyn(
	Pmono(\default,
		\freq, Pexprand(300, 900, inf),
		\dur, 0.25
	)
).play;

(
SynthDef(\modAtkEnv, { |out, value, ratio = 4, clipLo = 20, clipHi = 20000, decay = 0.08, lag = 0.02|
	Out.kr(out, (
		Lag.kr(value, lag) * EnvGen.kr(Env([1, ratio, 1], [0, decay], \exp))
	).clip(clipLo, clipHi))
}).add;
)

// 'k that 'map' thing is pretty slick
p = Pmsyn(
	Pmono(\default,
		\freq, Pexprand(300, 900, inf),
		\freqPlug, { |freq|
			Plug(\modAtkEnv, [value: freq, ratio: 8], map: (freq: \value))
		},
		\dur, 0.25
	)
).play;

p.stop;

(
p = Pmsyn(
	PmonoArtic(\default,
		\freq, Pexprand(300, 900, inf),
		\freqPlug, { |freq|
			Plug(\modAtkEnv, [value: freq, ratio: 8, decay: 0.03], map: (freq: \value))
		},
		\dur, 0.25,
		\legato, Pwrand([0.4, 1.05], [0.25, 0.75], inf)
	)
).play;
)

p.stop;


p = Pmsyn(Pbind(\freq, Pexprand(300, 900, inf), \dur, 0.25)).play;
p.stop;


// manual retriggering: problem: need to let user specify args

(
SynthDef(\modAtkEnv, { |out, value, ratio = 4, clipLo = 20, clipHi = 20000, decay = 0.08, lag = 0.02|
	var trig = NamedControl.tr(\trig, 1);
	Out.kr(out, (
		Lag.kr(value, lag) * EnvGen.kr(Env([1, ratio, 1], [0, decay], \exp), trig)
	).clip(clipLo, clipHi))
}).add;
)

(
p = Pmsyn(
	Pmono(\default,
		\freq, Pexprand(300, 900, inf),
		\freqPlug, { |freq, trig|
			Plug(\modAtkEnv, [value: freq, ratio: 8, trig: trig],
				map: (freq: \value)
			)
		},
		\dur, 0.25,
		'freq/trig', Pwrand([0, 1], [0.8, 0.2], inf),
		// Plug args can't be auto-detected in the event
		// List them under \args and these will be added
		// to the main SynthDef's args
		\args, ['freq/trig']
	)
).play;
)

p.stop;




// caching
s.dumpOSC(1);

(
x = Syn(\default, [
	freq: Plug { LFDNoise3.kr(3).exprange(200, 800) }
]);

y = Syn(\default, [
	freq: Plug { LFDNoise3.kr(3).exprange(200, 800) }
]);
)

[ "#bundle", 1,
  [ "/d_recv", DATA[214] ],  // x's Plug synthef
  [ "/sync", 1008 ]
]
[ "#bundle", 1,
  [ "/d_recv", DATA[214] ],  // y's Plug synthef
  [ "/sync", 1009 ]
]
[ "#bundle", 1,
  [ 9, "plugDef1006", 1000, 1, 1, "out", 0, "i_out", 0 ],
  [ 9, "default", 1001, 3, 1000, "freq", "c0" ]
]
[ "#bundle", 1,
  [ 9, "plugDef1007", 1002, 1, 1, "out", 1, "i_out", 1 ],
  [ 9, "default", 1003, 3, 1002, "freq", "c1" ]
]

x.release; y.release;


(
f = { LFDNoise3.kr(3).exprange(200, 800) };

x = Syn(\default, [
	freq: Plug(f)
]);

y = Syn(\default, [
	freq: Plug(f)
]);
)

[ "#bundle", 1,
  [ "/d_recv", DATA[214] ],  // one SynthDef for x
  [ "/sync", 1011 ]
]
[ "#bundle", 1,
  [ "/sync", ],              // y does not make a SynthDef
  [ "/sync", 1012 ]
]
[ "#bundle", 1,
  [ 9, "plugDef1010", 1004, 1, 1, "out", 0, "i_out", 0 ],
  [ 9, "default", 1005, 3, 1004, "freq", "c0" ]
]
[ "#bundle", 1,
  [ 9, "plugDef1010", 1006, 1, 1, "out", 1, "i_out", 1 ],
  [ 9, "default", 1007, 3, 1006, "freq", "c1" ]
]

x.release; y.release;


// Ex. C -- 'baseFreq' changes function context
// no cache hit
(
f = { |baseFreq = 440| { baseFreq * LFDNoise3.kr(3).exprange(0.5, 2) } };

x = Syn(\default, [
	freq: Plug(f.value(220))
]);

y = Syn(\default, [
	freq: Plug(f.value(440))
]);
)

x.release; y.release;


// Ex. D -- 'f' produces a Plug with an argument list
// Cache is hit in this case
(
f = { |baseFreq = 440|
	Plug({ |baseFreqArg|
		baseFreqArg * LFDNoise3.kr(3).exprange(0.5, 2)
	}, [baseFreqArg: baseFreq])
};

x = Syn(\default, [
	freq: f.value(220)
]);

y = Syn(\default, [
	freq: f.value(440)
]);
)

x.release; y.release;


// does cache

(
p = Pbind(
	\type, \syn,
	\instrument, \default,
	\freq, Plug {
		ExpRand(200, 500) * LFDNoise3.kr(3).exprange(0.95, 1.05)
	},
	\dur, Pexprand(0.2, 0.8, inf),
	\legato, Pexprand(0.7, 1.4, inf)
).play;
)

p.stop;


// does cache
(
p = Pbind(
	\type, \syn,
	\instrument, \default,
	\freq, Pexprand(200, 500, inf),
	\freqPlug, { |freq|
		Plug({ |freqSynthArg|
			freqSynthArg * LFDNoise3.kr(3).exprange(0.95, 1.05)
		}, [freqSynthArg: freq])
	},
	\dur, Pexprand(0.2, 0.8, inf),
	\legato, Pexprand(0.7, 1.4, inf)
).play;
)

p.stop;


// does not cache
(
p = Pbind(
	\type, \syn,
	\instrument, \default,
	\freq, Pexprand(200, 500, inf),
	\freqPlug, { |freq|
		Plug {
			freq * LFDNoise3.kr(3).exprange(0.95, 1.05)
		}
	},
	\dur, Pexprand(0.2, 0.8, inf),
	\legato, Pexprand(0.7, 1.4, inf)
).play;
)

p.stop;
