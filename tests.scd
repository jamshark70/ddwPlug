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
	var bndl, addAction, sendGate, ids;
	var msgFunc, instrumentName, offset, strumOffset;

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

	// if(strum == 0 and: { (sendGate and: { sustain.isArray })
	// or: { offset.isArray } or: { lag.isArray } }) {
	// 	bndl = flopTogether(
	// 		bndl,
	// 		[sustain, lag, offset]
	// 	);
	// 	#sustain, lag, offset = bndl[1].flop;
	// 	bndl = bndl[0];
	// } {
	// 	bndl = bndl.flop
	// };

	// produce a node id for each synth

	// ~syn = Syn(instrumentName, bndl, ~group, ~addAction);
	~group = ~group.value;
	~group = Group.basicNew(~server, ~group);  // seriously...?
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
		// I think I can't support this?
		// if (strum < 0) {
		// 	bndl = bndl.reverse;
		// 	ids = ids.reverse
		// };
		// this, I have no idea what to do with
		strumOffset = offset + Array.series(bndl.size, 0, strum.abs);
		~schedBundleArray.(
			lag, strumOffset, server, bndl, ~latency
		);
		if (sendGate) {
			if (~strumEndsTogether) {
				strumOffset = sustain + offset
			} {
				strumOffset = sustain + strumOffset
			};
			~schedBundleArray.(
				lag, strumOffset, server,
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

Event.default[\latency]



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
