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

y.release;

z = x.play;
z.release;



// simple cable
x = Syn(\default, [freq: Plug(\freqlfo, [rate: 3])]);
y = x.play;

y.release;

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

y = x.play;
)

y.release;

Object.dependantsDictionary.keysValuesDo { |key, value| [key, value].postln }; ""

y.controls.keysValuesDo { |k, v| [k, v].postln }; ""

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
y = x.play;

y.release;


// shared cable
(
var cable;
x = Syn(\default, [
	freq: Plug({ LFDNoise3.kr(8).exprange(200, 800) }),
	pan: cable = Plug.shared({ SinOsc.kr(SinOsc.kr(0.2).exprange(0.5, 4)) }),
	amp: cable
]);
y = x.play;
)

y.node.trace

y.release;

s.sendMsg(\n_trace, 1012);


// independent cables with ad-hoc Syn function
// note: should override rate based on function
// but we don't know the function's rate until resolving it

// note: Syn source function isn't analyzed for numChannels and rate
// bc the SynPlayer's output is the user's responsibility
// but in1 and in2 are

(
// note, not defined within the thing
var freqPlug = Plug({ LFDNoise3.kr(8).exprange(200, 800) });

x = Syn(
	{ (NamedControl.ar(\in1, [0, 0]) + NamedControl.ar(\in2, [0, 0])) /** 0.05*/ },
	[
		in1: Plug(\default, [freq: freqPlug]/*, \audio, 2*/),
		in2: Plug(\default, [freq: freqPlug]/*, \audio, 2*/)
	]
);
y = x.play;
)

y.nodeAt(0).trace

y.release;

s.audioBusAllocator.debug


// multichannel expansion
(
x = Syn(\default, [
	freq: (3..5).collect { |i|
		Plug({ (1.1 ** LFDNoise3.kr(3)) * (200 * i) })
	}
].debug("arglist"));
y = x.eventPlay;
)

s.sendMsg(\n_trace, 1001);

y.release;

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

e.put(\type, \synOff).play;



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

y.controls['*rate']

y.set('*freq/rate', 1.5);



v = Voicer(1, Syn(\default), [pan: Plug({ LFDNoise3.kr(1) })]);

v.trigger1(440);
v.release(440);

v.nodes[0].initArgs

s.sendMsg(\n_trace, 1000)