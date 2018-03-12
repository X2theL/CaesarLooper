// a reverse engineered version of the awesome vst plugin by Expert Sleepers
// TODO:
// - fix double rate resulting in skipped samples when writing
// - freeze mode
// - reverse
// - fading
// - Saturation
// - Filter
// - control delay time by changing beats / triplets (set numBeats after loop is recorded, Then change delay)
// - sync groups
CaesarLooper {
	classvar <syncGroups, <phasorGroup;

	var <maxDelay, <server, <syncMode=\none, <syncGroup, <beats=4, <triplet=false;
	var <buf, <looperGroup, <phasorBus, <globalInBus, <preAmpBus, <readBus, <fxBus, <globalOutBus;
	var <phasorSynth, <inputSynth, <reads, <writeSynth, <fxSynth, <mixSynth, <triggerSynth,  triggerOSCFunc;
	var timeAtRecStart, <isRecording=false, timeAtTapStart, <isTapping=false, <isTriggering=false, <triggerLevel;
	var <pitchInertia=1.0, <>delayInertiaFadeTime=0.02, <>delayInertia=false, <>digitalMode=false, <freezeMode;
	var <monoize=0.0, <initialPan=0.0;
	var <delay=2.0, <masterFeedback=0.5, <dryLevel=1.0, <effectLevel=0.8, <inputLevel=1.0;
	var <fadeInTime=3.0, <fadeOutTime=3.0, <clearAfterFade=false;
	var <punchInQuantize=false, <punchOutQuantize=false, <punchOutType=\none, <punchInInputLevel=1.0;
	var <punchOutInputLevel=0.0, pisil=true, posil=true;
	var <pitch=0.0, <pitchLFOSpeed=0.0, <pitchLFODepth=0.0;

	*new { arg inputBus, outputBus, maxDelay=30, server;
		^super.newCopyArgs ( maxDelay, server ).init( inputBus, outputBus );
	}

	init { arg in, out, smode, sgroup;
		server ?? {server = Server.default};
		reads = List.new;
		fork {
			buf = Buffer.alloc(server, server.sampleRate * maxDelay, 2);
			if (phasorGroup.isNil) {
				phasorGroup = Group.new(server, 'addToHead'); // global phasor group
			};
			looperGroup = Group.new(server, 'addToTail'); // always at the bottom
			phasorBus = Bus.audio(server, 2);
			if (in.isKindOf(Bus).not) { // set to first hardware in
				globalInBus = Bus.new('audio', server.options.numOutputBusChannels, 2, server);
			};
			if (out.isKindOf(Bus).not) { // set to first hardware out
				globalOutBus = Bus.new('audio', 0, 2, server);
			};
			preAmpBus = Bus.audio(server, 2); // out bus of input synth; write synth reads from this
			readBus = Bus.audio(server, 2); // out bus for read synths
			fxBus = Bus.audio(server, 2); // out bus for fx, aka feedbackBus
			server.sync;
			phasorSynth = Synth('caesarphasor', ['buf', buf, 'phasorBus', phasorBus, 'pitchInertia', pitchInertia], phasorGroup);

			inputSynth = Synth('caesarinput', ['inBus', globalInBus, 'preAmpBus', preAmpBus, 'globalOutBus', globalOutBus, 'feedbackBus', fxBus, 'masterFeedback', masterFeedback], looperGroup);

			fxSynth = Synth('caesarfx', ['readBus', readBus, 'fxBus', fxBus], inputSynth, 'addAfter');

			mixSynth = Synth('caesarmix', ['fxBus', fxBus, 'globalOutBus', globalOutBus, 'effectLevel', effectLevel], fxSynth, 'addAfter');

			writeSynth = Synth('caesarwrite', ['buf', buf, 'preAmpBus', preAmpBus, 'phasorBus', phasorBus], looperGroup, 'addToTail');

			this.addRead(1.0, 1.0, 0);
		};

	}

	// set syncGroup and deal with all consequences
	syncGroup_ {arg newGroup;
		if (syncGroup.isNil) { // there is no oldGroup

		}
	}

	addRead { arg div=0.5, level=0.5, pan=0;
		reads.add( CaesarRead(this, div, level, pan) );
	}

	removeRead { arg index;
		if (index.isNil) {
			reads.pop.free;
		} {
			reads.removeAt(index).free;
		}
	}

	// lo: 0, hi: 1.3
	masterFeedback_ { arg newVal;
		masterFeedback = newVal.clip(0, 1.3);
		inputSynth.set('masterFeedback', masterFeedback);
	}

	// lo: 0, hi: 2
	inputLevel_ { arg newVal;
		inputLevel = newVal.clip(0, 2);
		inputSynth.set('inputLevel', inputLevel);
	}

	// lo: 0, hi: 2
	dryLevel_ { arg newVal;
		dryLevel = newVal.clip(0, 2);
		inputSynth.set('dryLevel', dryLevel);
	}

	// lo: 0, hi: 2
	effectLevel_ { arg newVal;
		effectLevel = newVal.clip(0, 2);
		mixSynth.set('effectLevel', effectLevel);
	}

	// lo: 0, hi: 5
	pitchInertia_ { arg newVal;
		pitchInertia = newVal.clip(0, 5);
		phasorSynth.set('pitchInertia', pitchInertia);
	}

	// lo: 0, hi: 1
	monoize_ { arg newVal;
		monoize = newVal.clip(0, 1);
		inputSynth.set('monoize', monoize);
	}

	// lo: -1, hi: 1
	initialPan_ { arg newVal;
		initialPan = newVal.clip(-1, 1);
		inputSynth.set('initialPan', initialPan);
	}

	// lo: -12, hi: 12
	pitch_ { arg newVal;
		pitch = newVal.clip(-12, 12);
		phasorSynth.set('rate', pitch.midiratio);
	}

	// lo: 0, hi: 20
	pitchLFOSpeed_ { arg newVal;
		pitchLFOSpeed = newVal.clip(0, 20);
		this.changed(\pitchLFOSpeed);
	}

	// lo: 0, hi: 3000
	pitchLFODepth_ { arg newVal;
		pitchLFODepth = newVal.clip(0, 3000);
		this.changed(\pitchLFODepth);
	}

	tapeStop {
		phasorSynth.set('rate', 0);
	}

	tapeStart {
		phasorSynth.set('rate', pitch.midiratio);
	}

	// create a one shot trigger synth and OSCFunc that activates tapRec
	armAutoRec {
		if ( isTriggering.not ) {
			triggerSynth = Synth('caesartrigger', [], inputSynth, 'addAfter');
			triggerOSCFunc = OSCFunc({arg msg;
				"recording started".postln;
				if ( isRecording.not ) { this.tapRecord };
				isTriggering = false;
			}, '/tr', server.addr, nil, [triggerSynth.nodeID]).oneShot;
			isTriggering = true;
		}
	}

	triggerLevel_ { arg newVal;
		triggerLevel = newVal.clip(0.0001, 1.0);
		if ( isTriggering ) {
			triggerSynth.set('thresh', triggerLevel);
		}
	}

	freeze {}

	reverse {}

	// cannot be engaged while tap recording and is reset by it
	tapLength {
		if (isRecording.not) {
			if (isTapping) {
				this.delay_( (thisThread.seconds - timeAtTapStart).clip(0.005, maxDelay) );
				isTapping = false;
			} {
				timeAtTapStart = thisThread.seconds;
				isTapping = true;
			}
		}
	}

	delay_ { arg newVal;
		delay = newVal.clip(0.005, maxDelay);
		this.changed( \delay );
	}

	// can't call delay setter here because CaesarRead's update gets confused
	tapRecord {
		var newDelay;
		if (isTapping) {isTapping = false}; // aborts tapLength
		if ( isRecording ) {
			newDelay = (thisThread.seconds - timeAtRecStart).clip(0.005, maxDelay);
			delay = newDelay;
			this.changed(\recStop);
			this.effectLevel_(1);
			inputSynth.set('pr_feedback', 1.0); // bring back feedback
			if ( posil ) { this.inputLevel_( punchOutInputLevel ) };
			isRecording = false;
		} {
			inputSynth.set('pr_feedback', 0.0); // cut feedback
			this.effectLevel_(0);
			timeAtRecStart = thisThread.seconds;
			if ( pisil ) { this.inputLevel_( punchInInputLevel ) };
			isRecording = true;
		}
	}

	clear {
		buf.zero;
	}

	fade {}

	fadeOverride {}

	free {
		reads.do(_.free);
		looperGroup.free;
		buf.free;
		phasorSynth.free;
		// TODO: clean up shit in syncGroups
	}

	*initClass {
		syncGroups = 16.collect({IdentitySet.new});

		ServerBoot.add({
			// synth for initial panning, stereoization and feedback mixing
			SynthDef('caesarinput', {arg inBus=0, preAmpBus=0, globalOutBus=0, feedbackBus=88, inputLevel=1.0, dryLevel=1.0, masterFeedback=0.8, pr_feedback=1.0, monoize=0.0, initialPan=0.0;

				var feedbackIn = InFeedback.ar(feedbackBus, 2); // feedback output from fxBus
				var inputStereo = In.ar(inBus, 2);
				var inputMono = Mix(inputStereo);
				var pannedMono = Pan2.ar(inputMono, initialPan);
				var sig = (inputStereo * (1 - monoize)) + (pannedMono * monoize);

				Out.ar( globalOutBus, inputStereo * dryLevel ); // dry signal
				Out.ar( preAmpBus, (sig * inputLevel) + (feedbackIn * masterFeedback * pr_feedback) );
			}).add;

			SynthDef('caesarphasor', {arg buf, phasorBus=101, rate=1, pitchInertia=1;
				var phaseRate = Lag2.kr( rate, pitchInertia );
				var phase = Phasor.ar( 0, BufRateScale.kr(buf) * phaseRate , 0, BufFrames.kr(buf) );
				Out.ar(phasorBus, phase);
			}).add;

			SynthDef('caesarwrite', {arg buf, preAmpBus, phasorBus;
				var input = In.ar(preAmpBus, 2);
				var phase = In.ar(phasorBus, 1);
				BufWr.ar(input, buf, phase);
			}).add;

			// TODO: make swappable, put before caesarmix
			SynthDef('caesarfx', {arg readBus, fxBus, amp=1.0;
				var input = In.ar(readBus, 2);
				Out.ar(fxBus, input * amp);
			}).add;

			SynthDef('caesarmix', {arg fxBus, globalOutBus=0, effectLevel=0.8, gate=1;
				var input = In.ar(fxBus, 2);
				var sig = input *  effectLevel * EnvGen.ar(Env.asr(0.01, 1, 0.01), gate, doneAction:2);
				Out.ar(globalOutBus, sig);
			}).add;

			SynthDef('caesartrigger', { arg preampBus, thresh=0.4;
				var amp, trig;
				amp = Amplitude.kr( Mix.new( In.ar(preampBus, 2) ), 0.01, 0.1 );
				trig = amp > thresh;
				Linen.kr(trig, 0.01, 1, 0.01, doneAction:2);
				SendTrig.kr(Gate.kr(trig, trig), 235, 1);
			}).add;

		}, \all)
	}
}

// one CaesarLooper can have multiple
CaesarRead {
	var caesar, <divisor, <level, <pan, <synth;

	*new { arg caesar, divisor=1.0, level=1.0, pan=0;
		^super.newCopyArgs(caesar, divisor, level, pan).init;
	}

	init {
		caesar.addDependant(this);
		this.make;
	}

	divisor_ { arg newValue=1.0;
		if ( newValue <= 1.0 and: { newValue != divisor } ) {
			this.release;
			divisor = newValue;
			this.make;
		}
	}

	make {
		synth = Synth('caesarread', [
			'buf', caesar.buf,
			'phasorBus', caesar.phasorBus,
			'readBus', caesar.readBus,
			'amp', level,
			'pan', pan,
			'fade', caesar.delayInertiaFadeTime,
			'pitchInertia', caesar.pitchInertia,
			'offset', (caesar.delay * caesar.server.sampleRate * divisor).round,
			'lfoFreq', caesar.pitchLFOSpeed,
			'lfoDepth', caesar.pitchLFODepth
		], caesar.inputSynth, 'addAfter');
	}

	release {
		synth.release;
	}

	update { arg changer, what ...args;
		switch ( what ) { \recStop } {
			this.release;
			this.make;
		} { \delay } {
			if ( caesar.delayInertia ) {
				synth.set('offset', (caesar.delay * caesar.server.sampleRate * divisor).round );
			} {
				this.release;
				this.make;
			}
		} { \pitchLFOSpeed } {
			synth.set('lfoFreq', caesar.pitchLFOSpeed);
		} { \pitchLFODepth } {
			synth.set('lfoDepth', caesar.pitchLFODepth);
		};
	}

	level_ { arg newVal;
		level = newVal;
		synth.set( 'amp', level );
	}

	pan_ { arg newVal;
		pan = newVal.clip(-1, 1);
		synth.set( 'pan', pan );
	}

	free {
		this.release;
		caesar.removeDependant(this);
	}

	*initClass {
		ServerBoot.add({
			// reads offset ahead of write synth and wraps over buffer borders
			SynthDef('caesarread', {arg buf, phasorBus, readBus, amp=1.0, pan=0, gate=1, fade=0.1, pitchInertia=0.4, offset=10000, lfoFreq, lfoDepth;
				var phase, sig;

				phase = In.ar( phasorBus, 1 ) - Lag2.kr(offset, pitchInertia);
				phase = Wrap.ar( phase + Lag2.kr( SinOsc.kr(lfoFreq, 0, lfoDepth) ), 0, BufFrames.kr( buf ) );
				sig = BufRd.ar(2, buf, phase, 0) * Lag.kr(amp, fade) * EnvGen.ar( Env.asr(fade, 1, fade), gate, doneAction:2 );
				Out.ar( readBus, Balance2.ar( sig[0], sig[1], pan ) );
			}).add;
		}, \all);
	}
}