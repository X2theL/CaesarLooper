// a reverse engineered version of the awesome vst plugin by Expert Sleepers
// TODO:
// - sync groups
// - make globalInBus and globalOutBus work with private busses
CaesarLooper {
	classvar <syncGroups, <phasorGroup;

	var <maxDelay, <server, <syncMode=\none, <syncGroup, <beats=4, <triplet=false;
	var <buf, <looperGroup, <phasorBus, <globalInBus, <preAmpBus, <readBus, <fxBus, <globalOutBus, <fadeBus;
	var <phasorSynth, <inputSynth, <reads, <writeSynth, <fxSynth, <mixSynth, <triggerSynth,  triggerOSCFunc;
	var timeAtRecStart, <isRecording=false, timeAtTapStart, locked=false, phasePos, <isTapping=false, <isTriggering=false, <triggerLevel;
	var <pitchInertia=0.0, <delayInertiaFadeTime=0.05, <>delayInertia=false;
	var <isFrozen=false, <isReversed=false, <freezeRout, <monoize=0.0, <initialPan=0.0;
	var <delay=2.0, <masterFeedback=0.5, <dryLevel=1.0, <effectLevel=0.8, <inputLevel=1.0;
	var <>fadeInTime=3.0, <>fadeOutTime=3.0, <>fadeOutCompleteAction=\none, <fadeState, <>fadeSynth, <>fadeOSCFunc;
	var <>punchInInputLevel=1.0, <>punchOutInputLevel=0.0, <>pisil=true, <>posil=true;
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
			fadeBus = Bus.control(server, 1); // bus for fadeSynth
			fadeBus.set(1);
			server.sync;

			phasorSynth = Synth('caesarphasor', ['buf', buf, 'phasorBus', phasorBus, 'pitchInertia', pitchInertia], phasorGroup);

			inputSynth = Synth('caesarinput', ['inBus', globalInBus, 'preAmpBus', preAmpBus, 'globalOutBus', globalOutBus, 'feedbackBus', fxBus, 'masterFeedback', masterFeedback], looperGroup);

			fxSynth = Synth('caesarfx', ['readBus', readBus, 'fxBus', fxBus], inputSynth, 'addAfter');

			mixSynth = Synth('caesarmix', ['fxBus', fxBus, 'globalOutBus', globalOutBus, 'effectLevel', effectLevel], fxSynth, 'addAfter');

			writeSynth = Synth('caesarwrite', ['buf', buf, 'preAmpBus', preAmpBus, 'phasorBus', phasorBus], looperGroup, 'addToTail');

			this.addRead(1.0, 1.0, 0);

			this.fadeState_( FadeDefault.new ); // fade state machine
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

	// swaps the default post read fx for the provided one
	playFX { arg fx;

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
		this.changed(\pitchInertia); // notify reads
	}

	//
	delayInertiaFadeTime_ { arg newVal;
		delayInertiaFadeTime = newVal.clip(0, 0.3);
		this.changed(\fade);
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
		phasorSynth.set('rate', this.getRate);
	}

	getRate {
		var theRate = pitch.midiratio;
		if (isReversed) { theRate = theRate.neg };
		^theRate;
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
		if (isFrozen) { this.pr_freezeReset };
		phasorSynth.set('rate', 0);
	}

	tapeStart {
		if (isFrozen) { this.pr_freezeReset };
		phasorSynth.set('rate', this.getRate);
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

	// implements freezeMode \last from Augustus Loop
	freeze {
		if (locked or:{ isRecording }) {"locked".postln; ^nil};
		if ( isFrozen ) {
			this.pr_freezeReset;
		} {
			// cut input, switch inputSynth feedbackBus from fxBus to readBus
			inputSynth.set('feedbackBus', readBus);
			OSCFunc({arg msg;
				// Routine
				freezeRout = fork {
					this.pr_calcPhasePos(msg[3]);
					loop {
						if (locked.not) {
							// when writeSynth has ended
							OSCFunc({
								this.pr_bundledMake;
							}, '/n_end', argTemplate:[writeSynth.nodeID]).oneShot;
							this.pr_bundledRelease; // end write and reads
						};
						delay.wait;
					}
				}
			}, '/tr', argTemplate:[nil, 34]).oneShot;

			// trigger OSCFunc
			phasorSynth.set('t_getPhase', 1);
			isFrozen = true;
		}
	}

	// release write and all read synths together
	pr_bundledRelease {
		var bundle = List.new;
		bundle.add(writeSynth.releaseMsg);
		reads.do({ arg i;
			bundle.add(i.releaseMsg);
		});
		server.listSendBundle(nil, bundle);
	}

	// create new write/reads and set phasor in freezeRout and reverse
	pr_bundledMake {
		var bundle = List.new;
		writeSynth = Synth.basicNew('caesarwrite', server);
		bundle.add( writeSynth.newMsg(looperGroup, [
			'buf', buf,
			'preAmpBus', preAmpBus,
			'phasorBus', phasorBus
		], 'addToTail') );
		bundle.add( phasorSynth.setMsg(
			't_trigFromResetPos', 1,
			'resetPos', phasePos,
			'rate', this.getRate,
			'pitchInertia', pitchInertia
		) );
		reads.do({ arg i;
			bundle.add(i.newMsg);
		});
		server.listSendBundle(nil, bundle);
	}

	pr_freezeReset {
		freezeRout.stop;
		inputSynth.set('feedbackBus', fxBus);
		isFrozen = false;
	}

	// calculate new phase position with respect to isReversed and delay
	pr_calcPhasePos { arg oldPhase;
		var newPhasePos;
			if (isReversed) {
				newPhasePos = (oldPhase + (delay * server.sampleRate)).round.wrap(0, buf.numFrames);
			} {
				newPhasePos = (oldPhase - (delay * server.sampleRate)).round.wrap(0, buf.numFrames);
			};
		phasePos = newPhasePos; // var for the freeze loop
	}

	// new reverse creates new reads. write and phasor
	reverse {
		if (locked or:{ isRecording }) {"locked".postln; ^nil};
		locked = true; // don't let freeze get in the way while reversing
		OSCFunc({arg msg;

			OSCFunc({

				OSCFunc({ // when old write synth has ended
					"making new write synth".postln;
					this.pr_calcPhasePos(msg[3]);
					isReversed = isReversed.not; // BEFORE new synths get rate and offset
					this.pr_bundledMake;
					locked = false; // TODO: only safe when lock is released after phasor has reached new rate
				}, '/n_end', argTemplate:[writeSynth.nodeID]).oneShot;
				this.pr_bundledRelease;

			}, \tr, argTemplate:[nil, 35]).oneShot; // phasor sends trigger 35 when rate approaches 0
			phasorSynth.set('rate', 0);

		}, '/tr', argTemplate:[nil, 34]).oneShot; // 34 is id of the getPhase trigger
		phasorSynth.set('t_getPhase', 1); // get phase position
	}

	// cannot be engaged while tap recording and is reset by it
	tapLength {
		if (isFrozen) { this.pr_freezeReset };
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

	delay_ { arg newVal=1;
		if (isFrozen) { this.pr_freezeReset };
		delay = newVal.clip(0.005, maxDelay);
		this.changed( \delay );
	}

	// beats can be set with or without changing delay time
	beats_ {arg newVal=1, changeDelay=false;
		var oldBeats = beats;
		beats = newVal;
		if (changeDelay) {
			this.delay_( (delay / oldBeats) * beats );
		}
	}

	// times 2/3
	triplet_ { arg newVal=false, changeDelay=false;
		if ( triplet != newVal ) {
			triplet = newVal;
			if ( changeDelay ) {
				if ( triplet ) {
					this.delay_( delay * 0.6667 );
				} {
					this.delay_( delay * 1.5 );
				}
			}
		}
	}

	// can't call delay setter here because CaesarRead's update gets confused
	tapRecord {
		var newDelay;
		if (locked) {"locked".postln; ^nil};
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
			if (isFrozen) { this.pr_freezeReset };
			inputSynth.set('pr_feedback', 0.0); // cut feedback
			this.effectLevel_(0);
			timeAtRecStart = thisThread.seconds;
			if ( pisil ) { this.inputLevel_( punchInInputLevel ) };
			isRecording = true;
		}
	}

	clear {
		buf.zero;
		this.pr_freezeReset;
	}

	// see FadeState for implementation of state machine
	fade {
		fadeState.fade(this);
	}

	fadeOverride {
		fadeState.fadeOverride(this);
	}

	fadeState_ { arg newState;
		fadeState = newState;
	}

	free {
		reads.do(_.free);
		looperGroup.free;
		fadeBus.free;
		buf.free;
		phasorSynth.free;
		phasorBus.free;
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

			// sends notification when rate approaches 0
			SynthDef('caesarphasor', {arg buf, phasorBus=101, rate=1, pitchInertia=1, t_getPhase=0, resetPos=0, t_trigFromResetPos=1, delay=2;
				var phaseRate = Lag2.kr( rate, pitchInertia );
				var phase = Phasor.ar( t_trigFromResetPos,
					BufRateScale.kr(buf) * phaseRate,
					0,
					BufFrames.kr(buf),
					resetPos ).round;
				SendTrig.kr(t_getPhase, 34, phase);
				SendTrig.kr( phaseRate.abs < 0.0001, 35, 1);
				Out.ar(phasorBus, phase);
			}).add;

			SynthDef('caesarwrite', {arg buf, preAmpBus, phasorBus, gate=1, fadeIn=0.05, fadeOut=0.05;
				var input = In.ar(preAmpBus, 2);
				var phase = Wrap.ar( In.ar(phasorBus, 1), 0, BufFrames.kr(buf) );
				var env = EnvGen.kr(Env.asr(fadeIn, 1, fadeOut), gate, doneAction:2);
				IBufWr.ar(input * env, buf, phase, 1);
			}).add;

			// TODO: make swappable, put before caesarmix
			SynthDef('caesarfxdummy', {arg readBus, fxBus, amp=1.0;
				var input = In.ar(readBus, 2);
				Out.ar(fxBus, input * amp);
			}).add;

			SynthDef('caesarfx', { arg readBus, fxBus, preGain=1, postGain=1, hiDamp=0, loDamp=0, freq=440, q = 1, wet=0, type=0;
				var dry, fx;
				dry = In.ar(readBus, 2);
				fx = (dry * preGain).softclip * postGain;
				fx = BHiShelf.ar(fx, 7000, 1, hiDamp);
				fx = BLowShelf.ar(fx, 300, 1, loDamp);
				fx = SelectX.ar(type, [
					fx,
					RLPF.ar(fx, freq, q),
					RHPF.ar(fx, freq, q)
				]);
				fx = XFade2.ar(dry, fx, wet * 2 - 1);
				Out.ar(fxBus, fx);
			}).add;

			SynthDef('caesarmix', {arg fxBus, globalOutBus=0, fadeBus, effectLevel=0.8, gate=1;
				var input = In.ar(fxBus, 2);
				var fadeEnv = In.kr(fadeBus, 1);
				var sig = input *  effectLevel * fadeEnv *EnvGen.ar(Env.asr(0.01, 1, 0.01), gate);
				Out.ar(globalOutBus, sig);
			}).add;

			SynthDef('caesartrigger', { arg preampBus, thresh=0.4;
				var amp, trig;
				amp = Amplitude.kr( Mix.new( In.ar(preampBus, 2) ), 0.01, 0.1 );
				trig = amp > thresh;
				Linen.kr(trig, 0.01, 1, 0.01, doneAction:2);
				SendTrig.kr(Gate.kr(trig, trig), 235, 1);
			}).add;

			// issue: emits a ghost trig of 1 at the start of the synth;
			SynthDef('caesarfadeout', { arg fadeBus, fadeInTime=4, fadeOutTime=4, gate=0;
				var env = EnvGen.kr(Env.new( [ 1, 0, 1], [ fadeOutTime, fadeInTime], [-4, 4], 1), gate);
				SendTrig.kr( DetectSilence.kr(env) + Done.kr(env), 101, env);
				Out.kr(fadeBus, env );
			}).add;

			SynthDef('caesarfadein', { arg fadeBus, fadeInTime=4, fadeOutTime=4, gate=1;
				var env = EnvGen.kr(Env.new( [ 0, 1, 0], [ fadeInTime, fadeOutTime], [-4, 4], 1), gate);
				SendTrig.kr( DetectSilence.kr(env) + Trig.kr(env - 0.999), 101, env);
				Out.kr(fadeBus, env );
			}).add;

		}, \all)
	}
}

// state machine for fading in/out etc
FadeState {
	*new { ^super.new }
	fade {}
	fadeOverride {}
}

FadeDefault : FadeState {
	// start fade out
	fade { arg caesar;
		caesar.fadeSynth = Synth('caesarfadeout', ['fadeBus', caesar.fadeBus,
			'fadeInTime', caesar.fadeInTime, 'fadeOutTime', caesar.fadeOutTime, 'gate', 1]);
		caesar.fadeOSCFunc = OSCFunc({ arg msg;
			//msg.postln;
			if ( msg[3] == 0 ) {
				caesar.fadeSynth.free;
				caesar.fadeOSCFunc.free;
				caesar.fadeState_( FadeOutCompleted.new(caesar) );
			} { // this should protect from the ghost event at synth creation time
				if ( caesar.fadeState.isKindOf(FadeInStarted) ) {
					caesar.fadeSynth.free;
					caesar.fadeOSCFunc.free; // can't do one shot here because of ghost trig
					caesar.fadeState_(FadeDefault.new);
				}
			}
		}, '/tr', caesar.server.addr, nil, [caesar.fadeSynth.nodeID]);
		caesar.fadeState_(FadeOutStarted.new);
	}

	fadeOverride {}
}

FadeOutStarted : FadeState {

	fade { arg caesar;
		caesar.fadeSynth.set('gate', 0); // fade back in again
		caesar.fadeState_(FadeInStarted.new);
	}

	fadeOverride { arg caesar;
		caesar.fadeBus.set(0);
		caesar.fadeSynth.free;
		caesar.fadeOSCFunc.free;
		caesar.fadeState_( FadeOutCompleted.new(caesar) );
	}
}

FadeOutCompleted : FadeState {

	*new { arg caesar; ^super.new.init(caesar) }

	init { arg caesar;
		if (caesar.fadeOutCompleteAction == \clear ) {
			caesar.clear;
		} {
			if ( caesar.fadeOutCompleteAction == \clear2 ) {
				caesar.clear;
				caesar.fadeBus.set(1);
				caesar.fadeState_( FadeDefault.new );
			}
		}
	}

	fade { arg caesar;
		caesar.fadeSynth = Synth('caesarfadein', ['fadeBus', caesar.fadeBus,
			'fadeInTime', caesar.fadeInTime, 'fadeOutTime', caesar.fadeOutTime, 'gate', 1]);
		caesar.fadeOSCFunc = OSCFunc({ arg msg;
			// msg.postln;
			if ( msg[3] == 0 ) {
				caesar.fadeSynth.free;
				caesar.fadeOSCFunc.free;
				caesar.fadeState_( FadeOutCompleted.new (caesar) );
			} { // ghost event should not appear at all with caesarfadein
				if ( caesar.fadeState.isKindOf(FadeInStarted) ) {
					caesar.fadeSynth.free;
					caesar.fadeOSCFunc.free; // TODO one shots
					caesar.fadeState_(FadeDefault.new);
				}
			}
		}, '/tr', caesar.server.addr, nil, [caesar.fadeSynth.nodeID]);
		caesar.fadeState_(FadeInStarted.new);
	}

	fadeOverride { arg caesar;
		caesar.fadeBus.set(1);
		caesar.fadeState_( FadeDefault.new );
	}
}

FadeInStarted : FadeState {

	fade { arg caesar;
		caesar.fadeSynth.set('gate', 0); // fade back out again
		caesar.fadeState_(FadeOutStarted.new);
	}

	fadeOverride { arg caesar;
		caesar.fadeBus.set(1);
		caesar.fadeSynth.free;
		caesar.fadeOSCFunc.free;
		caesar.fadeState_( FadeDefault.new );
	}
}

// one CaesarLooper can have multiple "read heads"
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
			'offset', this.pr_offset,
			'lfoFreq', caesar.pitchLFOSpeed,
			'lfoDepth', caesar.pitchLFODepth
		], caesar.inputSynth, 'addAfter');
	}

	// checks if caesar is reversed
	pr_offset {
		var offset = ( caesar.delay * caesar.server.sampleRate * divisor ).round;
		if (caesar.isReversed) {
			offset = offset.neg;
		};
		^offset;
	}

	release {
		synth.release;
	}

	// for bundling with write and phasor msgs
	newMsg {
		synth = Synth.basicNew('caesarread', caesar.server);
		^synth.newMsg(caesar.inputSynth, [
			'buf', caesar.buf,
			'phasorBus', caesar.phasorBus,
			'readBus', caesar.readBus,
			'amp', level,
			'pan', pan,
			'fade', caesar.delayInertiaFadeTime,
			'pitchInertia', caesar.pitchInertia,
			'offset', this.pr_offset,
			'lfoFreq', caesar.pitchLFOSpeed,
			'lfoDepth', caesar.pitchLFODepth
		], 'addAfter');
	}

	// used by freeze and reverse to bundle write and reads release
	releaseMsg { arg bundle;
		^synth.releaseMsg;
	}

	update { arg changer, what ...args;
		switch ( what ) { \recStop } {
			this.release;
			this.make;
		} { \delay } { // TODO: different when reversed
			if ( caesar.delayInertia ) {
				synth.set('offset', this.pr_offset );
			} {
				this.release;
				this.make;
			}
		} { \pitchLFOSpeed } {
			synth.set('lfoFreq', caesar.pitchLFOSpeed);
		} { \pitchLFODepth } {
			synth.set('lfoDepth', caesar.pitchLFODepth);
		} { \fade } {
			synth.set('fade', caesar.delayInertiaFadeTime);
		} { \pitchInertia } {
			synth.set('pitchInertia', caesar.pitchInertia);
		} { \release } {
			this.release;
		} { \make } {
			this.make;
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
			// reads offset ahead of write synth except when reversed
			SynthDef('caesarread', {arg buf, phasorBus, readBus, amp=1.0, pan=0, gate=1, fade=0.05, pitchInertia=0.4, offset=10000, lfoFreq, lfoDepth, t_getPhase=0;
				var phase, sig;

				phase = In.ar( phasorBus, 1 ) - Lag2.ar(K2A.ar(offset), pitchInertia);
				phase = Wrap.ar( phase + Lag2.ar( SinOsc.ar(lfoFreq, 0, lfoDepth) ), 0, BufFrames.kr( buf ) ).round;
				sig = BufRd.ar(2, buf, phase, 0, 4) * Lag.kr(amp, fade) * EnvGen.ar( Env.asr(fade, 1, fade), gate, doneAction:2 );
				pan = pan + 1; // magic pan solution
				sig = [ pan.linlin( 1, 2, 1, 0 ) * sig[0], pan.clip( 0, 1 ) * sig[1] ];
				//SendTrig.kr(t_getPhase, 44, phase);
				Out.ar( readBus, sig );
			}).add;
		}, \all);
	}
}