// plug tests

// OSCBundle

~debugAddr = DebugNetAddr("127.0.0.1", 57110);
~saveAddr = s.addr;

s.addr = ~debugAddr;
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
(
SynthDef(\freqlfo, { |out, rate = 5, low = 200, high = 800|
	Out.kr(out, LFDNoise3.kr(rate).exprange(low, high));
}).add;
)

x = Syn(\default, [freq: Cable(\freqlfo, [rate: 3])]);
y = x.play;

y.release;

s.controlBusAllocator.debug  // all freed, good!


// nested cable
x = Syn(\default, [
	freq: Cable(
		\freqlfo,
		[rate: Cable(
			\freqlfo, [rate: 0.7, low: 1, high: 70]
		)]
	)
]);

y = x.play;

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
** [a SynPlayer, IdentitySet[AutoReleaseBus(control, 0, 1, localhost), a Cable, a Cable, AutoReleaseBus(control, 1, 1, localhost)]]

// after -- back to original state, good
[class ProxyChain, IdentitySet[a Function]]
[class Server, IdentitySet[class MixerChannel]]
[OSCFunc(/mixerChBadValue, a NetAddr(127.0.0.1, 57110), nil, nil), IdentitySet[an OSCMessageDispatcher]]
[a Volume, IdentitySet[a SimpleController]]
[localhost, IdentitySet[a SimpleController]]
[OSCFunc(/status.reply, a NetAddr(127.0.0.1, 57110), nil, nil), IdentitySet[an OSCMessageDispatcher]]


// cable func
x = Syn(\default, [
	freq: Cable({ LFDNoise3.kr(8).exprange(200, 800) })
]);
y = x.play;

y.release;


// shared cable
(
var cable;
x = Syn(\default, [
	freq: Cable({ LFDNoise3.kr(8).exprange(200, 800) }),
	pan: cable = Cable.shared({ SinOsc.kr(SinOsc.kr(0.2).exprange(0.5, 4)) }),
	amp: cable
]);
y = x.play;
)

y.node.trace

y.release;

s.sendMsg(\n_trace, 1012);