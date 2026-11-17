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

    // Ask the OS what every currently-active recorder is capturing: audio source
    // (1=MIC, 6=VOICE_RECOGNITION, 7=VOICE_COMMUNICATION, ...) and exact format.
    // Run this WHILE on a WhatsApp call to read its real capture rate — no guessing.
    static void probe() throws Exception {
        Class.forName("android.os.Looper").getMethod("prepareMainLooper").invoke(null);
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "audio");
        Object svc = Class.forName("android.media.IAudioService$Stub")
                .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        Method gm = null;
        for (Method mm : svc.getClass().getMethods())
            if (mm.getName().equals("getActiveRecordingConfigurations")) { gm = mm; break; }
        if (gm == null) { say("no getActiveRecordingConfigurations method found"); return; }
        Object list = (gm.getParameterTypes().length == 0) ? gm.invoke(svc) : gm.invoke(svc, "com.android.shell");
        java.util.List<?> cfgs = (java.util.List<?>) list;
        say("active recordings = " + (cfgs == null ? "null" : cfgs.size()));
        if (cfgs == null) return;
        int k = 0;
        for (Object cfg : cfgs) {
            try {
                Object src = call(cfg, "getClientAudioSource", new Class[]{});
                Object cf = call(cfg, "getClientFormat", new Class[]{});
                Object df = call(cfg, "getFormat", new Class[]{});
                StringBuilder s = new StringBuilder("[" + (k++) + "] src=" + src);
                if (cf != null) s.append(" clientRate=").append(call(cf, "getSampleRate", new Class[]{}))
                        .append(" clientCh=").append(call(cf, "getChannelCount", new Class[]{}));
                if (df != null) s.append(" devRate=").append(call(df, "getSampleRate", new Class[]{}))
                        .append(" devCh=").append(call(df, "getChannelCount", new Class[]{}));
                say(s.toString());
            } catch (Exception e) { say("cfg read error: " + e); }
        }
        if (k == 0) say("no active recorders — make sure the call is connected before running probe");
    }

    public static void main(String[] args) throws Exception {
        say("start");
        if (args.length > 0 && args[0].equalsIgnoreCase("probe")) { probe(); return; }
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

        // --- Get the injection track and feed audio ---
        Object track = call(policy, "createAudioTrackSource", new Class[]{cMix}, mix);
        call(track, "play", new Class[]{});

        short[] one;
        if (args.length > 2) {
            // 3rd arg = path to a 16-bit little-endian PCM mono file at `sr`
            // (a WAV header, if present, is auto-skipped). Convert a song with:
            //   ffmpeg -i song.mp3 -ac 1 -ar 16000 -f s16le song.pcm
            String path = args[2];
            java.io.File f = new java.io.File(path);
            byte[] raw = new byte[(int) f.length()];
            java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.FileInputStream(f));
            dis.readFully(raw);
            dis.close();
            int off = (raw.length > 44 && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F') ? 44 : 0;
            int n = (raw.length - off) / 2;
            one = new short[n];
            for (int i = 0; i < n; i++) one[i] = (short) ((raw[off + 2 * i] & 0xff) | (raw[off + 2 * i + 1] << 8));
            say("INJECTING file '" + path + "' (" + n + " samples) on loop for 90s");
        } else {
            // Siren sweep 400<->2000 Hz — unmistakable even if the rate is slightly off.
            int len = sr * 3 / 2; // 1.5 s
            one = new short[len];
            double phase = 0.0;
            for (int i = 0; i < len; i++) {
                double t = (double) i / len;
                double tri = (t < 0.5) ? (t * 2.0) : (2.0 - t * 2.0);
                double f = 400.0 + 1600.0 * tri;
                phase += 2.0 * Math.PI * f / sr;
                one[i] = (short) (Math.sin(phase) * 16000);
            }
            say("INJECTING siren sweep for 90s — be on the call NOW");
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
