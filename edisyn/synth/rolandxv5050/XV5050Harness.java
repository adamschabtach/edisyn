/***
    Copyright 2026 by Adam Schabtach
    Licensed under the Apache License version 2.0
*/

package edisyn.synth.rolandxv5050;

import edisyn.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
   Headless tests for the Roland XV-5050 editor. Not part of Edisyn's UI.

   <pre>
   java -cp libraries/coremidi4j-1.6.jar:. edisyn.synth.rolandxv5050.XV5050Harness [dir-of-syx-files]
   java -cp libraries/coremidi4j-1.6.jar:. edisyn.synth.rolandxv5050.XV5050Harness --snapshot &lt;png dir&gt;
   </pre>

   Checks the schema and the protocol without a synthesizer: that the generated table
   is self-consistent, that the init patch loads, that address arithmetic and checksums
   match the manual, that a model survives emit -> parse -> emit byte-identically (from
   the defaults and from pseudo-random values), that every single-parameter DT1 decodes
   back to the value it was built from, and that the recognizer claims patches and only
   patches. Given a directory, every <tt>.syx</tt> file in it is round-tripped too.

   It builds the editor's Swing widgets, so it needs a display. Exit status 0 when
   everything passes.
*/

public class XV5050Harness
    {
    static int failures = 0, checks = 0;

    static boolean check(boolean ok, String what)
        {
        checks++;
        if (!ok)
            {
            failures++;
            if (failures <= 40) System.out.println("FAIL: " + what);
            }
        return ok;
        }

    public static void main(String[] args)
        {
        try { run(args); }
        catch (Throwable t) { t.printStackTrace(System.out); System.out.println("XV5050 HARNESS CRASHED"); System.exit(3); }
        }

    static void run(String[] args) throws Exception
        {
        RolandXV5050.testMode = true;
        RolandXV5050 synth = new RolandXV5050();
        synth.setSendMIDI(false);
        synth.getUndo().setWillPush(false);

        if (args.length == 2 && args[0].equals("--snapshot"))
            { snapshot(synth, Paths.get(args[1])); System.exit(0); }

        schemaSelfConsistency();
        addressArithmetic();
        checksums();
        nibbles();
        displays(synth);
        initPatch(synth);
        mutationPolicy(synth);
        roundTripDefaults(synth);
        roundTripRandom(synth);
        singleParameterEdits(synth);
        recognizer(synth);

        if (args.length > 0) roundTripFiles(synth, Paths.get(args[0]));

        System.out.println(checks + " checks, " + failures + " failures");
        System.out.println(failures == 0 ? "XV5050 HARNESS PASSED" : "XV5050 HARNESS FAILED");
        System.exit(failures == 0 ? 0 : 1);
        }

    // ------------------------------------------------------------------- the table

    static void schemaSelfConsistency()
        {
        int total = 0, data = 0;
        Set<String> keys = new HashSet<>();
        for (RolandXV5050Params.Block b : RolandXV5050Params.PATCH)
            {
            data += b.size;
            boolean[] covered = new boolean[b.size];
            if (b == RolandXV5050.COMMON_BLOCK)
                for (int i = 0; i < RolandXV5050Params.NAME_LENGTH; i++) covered[i] = true;

            for (RolandXV5050Params.P p : b.params)
                {
                total++;
                check(keys.add(p.key), "duplicate key " + p.key);
                check(p.min <= p.max, p.key + ": min " + p.min + " > max " + p.max);
                check(p.def >= p.min && p.def <= p.max,
                    p.key + ": default " + p.def + " outside " + p.min + ".." + p.max);
                check(p.metricMin >= p.min && p.metricMax <= p.max,
                    p.key + ": metric range outside the raw range");
                check(p.offset >= 0 && p.offset + p.bytes <= b.size,
                    p.key + ": bytes " + p.offset + ".." + (p.offset + p.bytes - 1) + " outside block " + b.name);
                for (int i = 0; i < p.bytes; i++)
                    {
                    check(!covered[p.offset + i],
                        b.name + ": byte " + (p.offset + i) + " claimed twice (" + p.key + ")");
                    covered[p.offset + i] = true;
                    }
                // Every value in range must survive the wire encoding.
                int limit = p.nibbled ? (1 << (4 * p.bytes)) - 1 : 127;
                check(p.max <= limit, p.key + ": max " + p.max + " does not fit in "
                    + p.bytes + (p.nibbled ? " nibbled" : " plain") + " byte(s)");

                if (p.labels != null)
                    check(p.labels.length == p.max - p.min + 1,
                        p.key + ": " + p.labels.length + " labels for range " + p.min + ".." + p.max);
                if (p.widget != RolandXV5050Params.WIDGET_DIAL)
                    check(p.labels != null, p.key + ": a chooser or checkbox without labels");
                }
            for (int i = 0; i < b.size; i++)
                check(covered[i], b.name + ": byte " + i + " belongs to no parameter");
            }
        check(total == 695, "695 parameters expected, found " + total);
        check(data == 948, "948 data bytes expected, found " + data);
        check(RolandXV5050Params.PATCH.length == 9, "nine blocks expected");
        check(RolandXV5050Params.WAVE_NAMES.length == 1083,
            "1083 internal waves expected, found " + RolandXV5050Params.WAVE_NAMES.length);
        check(RolandXV5050Params.NOTE_VALUES.length == 22, "22 note values expected");
        check(RolandXV5050Params.MFX_TYPES.length == 128, "128 MFX type slots expected");
        check(RolandXV5050Params.CATEGORIES.length == 128, "128 category slots expected");
        }

    // -------------------------------------------------------------- the protocol

    static void addressArithmetic()
        {
        // The manual's example: 00 00 01 09 is 137, not 265.
        check(RolandXV5050Sysex.lin(0x00, 0x00, 0x01, 0x09) == 137, "00 00 01 09 is 137");
        check(RolandXV5050Params.PATCH_SPAN == RolandXV5050Sysex.lin(0, 0, 0x27, 0x09),
            "the patch span is 00 00 27 09");

        // Tone 4 ends exactly at the span.
        RolandXV5050Params.Block tone4 = RolandXV5050Params.PATCH[8];
        check(tone4.name.equals("tone4"), "block 8 is tone4");
        check((tone4.address << 7) + tone4.size == RolandXV5050Params.PATCH_SPAN,
            "tone4 ends at the patch span: " + ((tone4.address << 7) + tone4.size));

        // Round-tripping an address through its bytes.
        int[] probes = { 0, 137, 5001, RolandXV5050Sysex.TEMP_PATCH, RolandXV5050Sysex.userPatch(127),
                         RolandXV5050Sysex.TEMP_PART + 15 * RolandXV5050Sysex.PART_STRIDE };
        for (int a : probes)
            {
            byte[] bytes = RolandXV5050Sysex.address(a);
            int back = RolandXV5050Sysex.lin(bytes[0], bytes[1], bytes[2], bytes[3]);
            check(back == a, "address round trip for " + a);
            for (byte b : bytes) check((b & 0x80) == 0, "address byte is 7-bit for " + a);
            }

        // The manual: part 16's temporary patch is at 14 60 00 00.
        check(RolandXV5050Sysex.TEMP_PART + 15 * RolandXV5050Sysex.PART_STRIDE
            == RolandXV5050Sysex.lin(0x14, 0x60, 0x00, 0x00), "part 16 is at 14 60 00 00");
        // And user patch 128 at 30 7F 00 00.
        check(RolandXV5050Sysex.userPatch(127) == RolandXV5050Sysex.lin(0x30, 0x7F, 0x00, 0x00),
            "user patch 128 is at 30 7F 00 00");
        }

    static void checksums()
        {
        // sum = (128 - ((address + data) % 128)) % 128
        byte[] msg = RolandXV5050Sysex.dt1((byte)0x10, RolandXV5050Sysex.SETUP, 0);
        check(RolandXV5050Sysex.checksumOK(msg), "the Patch-mode DT1 checksums");
        int sum = 0;
        for (int i = RolandXV5050Sysex.ADDRESS_OFFSET; i < msg.length - 1; i++) sum += msg[i];
        check((sum & 0x7F) == 0, "address + data + checksum is 0 mod 128");

        // A hand-computed case: address 1F 00 00 00, one data byte 0x7F.
        byte[] m2 = RolandXV5050Sysex.dt1((byte)0x10, RolandXV5050Sysex.TEMP_PATCH, 0x7F);
        int expect = (0x80 - ((0x1F + 0x7F) & 0x7F)) & 0x7F;
        check((m2[m2.length - 2] & 0xFF) == expect, "checksum of 1F 00 00 00 / 7F is " + expect);

        byte[] rq = RolandXV5050Sysex.rq1((byte)0x10, RolandXV5050Sysex.TEMP_PATCH, RolandXV5050Params.PATCH_SPAN);
        check(rq.length == 16, "an RQ1 is 16 bytes");
        check(rq[5] == 0x11, "an RQ1 uses command 11");
        int rqsum = 0;
        for (int i = RolandXV5050Sysex.ADDRESS_OFFSET; i < rq.length - 1; i++) rqsum += rq[i];
        check((rqsum & 0x7F) == 0, "the RQ1 checksums");
        }

    static void nibbles()
        {
        int[][] cases = { { 137, 2 }, { 0, 2 }, { 149, 2 }, { 250, 2 }, { 255, 2 },
                          { 12768, 4 }, { 32768, 4 }, { 52768, 4 }, { 16384, 4 }, { 65535, 4 } };
        for (int[] c : cases)
            {
            byte[] buf = new byte[c[1]];
            RolandXV5050Sysex.putNibbled(buf, 0, c[0], c[1]);
            for (byte b : buf) check((b & 0xF0) == 0, "nibble is 4-bit for " + c[0]);
            check(RolandXV5050Sysex.getNibbled(buf, 0, c[1]) == c[0], "nibble round trip for " + c[0]);
            }
        // Most significant nibble first.
        byte[] buf = new byte[4];
        RolandXV5050Sysex.putNibbled(buf, 0, 0xCE20, 4);
        check(buf[0] == 0x0C && buf[1] == 0x0E && buf[2] == 0x02 && buf[3] == 0x00,
            "52768 nibbles to 0C 0E 02 00");
        }

    static void displays(RolandXV5050 synth)
        {
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("pan"), 64).equals("0"), "patch pan 64 is 0");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("pan"), 0).equals("L64"), "patch pan 0 is L64");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("pan"), 127).equals("63R"), "patch pan 127 is 63R");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("coarsetune"), 64).equals("0"), "coarse tune 64 is 0");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("coarsetune"), 16).equals("-48"), "coarse tune 16 is -48");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("coarsetune"), 112).equals("+48"), "coarse tune 112 is +48");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("tone1pankeyfollow"), 64).equals("0"),
            "pan keyfollow 64 is 0");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("tone1pankeyfollow"), 74).equals("100"),
            "pan keyfollow 74 is 100");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("tone1pankeyfollow"), 54).equals("-100"),
            "pan keyfollow 54 is -100");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("tone1wavepitchkeyfollow"), 74).equals("100"),
            "pitch keyfollow 74 is 100");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("tmt1keyboardrangelower"), 60).equals("C4"),
            "note 60 is C4");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("tmt1keyboardrangelower"), 0).equals("C-1"),
            "note 0 is C-1");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("tmt1keyboardrangelower"), 127).equals("G9"),
            "note 127 is G9");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("mfxparameter1"), 32768).equals("0"),
            "an MFX slot at 32768 is 0");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("mfxparameter1"), 12768).equals("-20000"),
            "an MFX slot at 12768 is -20000");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("mfxparameter1"), 52768).equals("+20000"),
            "an MFX slot at 52768 is +20000");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("tone1delaytime"), 0).equals("0"),
            "tone delay time 0 is 0");
        check(RolandXV5050.display(RolandXV5050.BY_KEY.get("tone1delaytime"), 128).equals("1/64T"),
            "tone delay time 128 is the first note value");
        check(RolandXV5050.BY_KEY.get("tone1delaytime").metricMax == 127,
            "tone delay time is metric only to 127");

        // Captions never come back empty, and dials get at most two lines.
        for (String[] ins : RolandXV5050Layout.LAYOUT)
            if (ins[0].equals("param"))
                {
                check(ins[2] != null && !ins[2].trim().isEmpty(), "empty caption for " + ins[1]);
                String[] lines = RolandXV5050.splitCaption(ins[2]);
                check(lines[0] != null && !lines[0].trim().isEmpty(), "empty first caption line for " + ins[1]);
                }
        }

    static void initPatch(RolandXV5050 synth)
        {
        check(synth.getModel().get("name", "").startsWith("INIT PATCH"),
            "the init patch loaded (name '" + synth.getModel().get("name", "") + "')");
        for (RolandXV5050Params.Block b : RolandXV5050Params.PATCH)
            for (RolandXV5050Params.P p : b.params)
                {
                check(synth.getModel().exists(p.key), p.key + " is missing from the loaded init patch");
                int v = synth.getModel().get(p.key, Integer.MIN_VALUE);
                check(v >= p.min && v <= p.max, p.key + " = " + v + " outside " + p.min + ".." + p.max);
                }
        // Tone 1 plays an internal wave; Tones 2-4 are switched off.
        check(synth.getModel().get("tmt1toneswitch", -1) == 1, "the init patch switches Tone 1 on");
        for (int n = 2; n <= 4; n++)
            check(synth.getModel().get("tmt" + n + "toneswitch", -1) == 0,
                "the init patch switches Tone " + n + " off");
        check(synth.getModel().get("tone1wavenumberlmono", -1) == 1, "Tone 1 plays wave 1");
        check(synth.getModel().get("tone1wavegrouptype", -1) == 0, "Tone 1 uses the internal wave group");
        }

    static void mutationPolicy(RolandXV5050 synth)
        {
        Model m = synth.getModel();
        for (String key : new String[] { "mfxtype", "chorustype", "reverbtype", "category" })
            check(m.getStatus(key) == Model.STATUS_IMMUTABLE, key + " should be immutable");
        // Wave choices can be mutated, but not until the user says so.
        for (String key : new String[] { "tone1wavenumberlmono", "tone1wavenumberr",
                                         "tone1wavegroupid", "tone1wavegrouptype" })
            {
            check(m.getStatus(key) == Model.STATUS_FREE, key + " should stay mutable-in-principle");
            check(!synth.mutationMap.isFree(key), key + " should start with mutation off");
            }
        for (String key : new String[] { "mfxparameter1", "chorusparameter1", "reverbparameter1" })
            check(!synth.mutationMap.isFree(key), key + " should start with mutation off");
        // Ordinary parameters mutate by default.
        for (String key : new String[] { "tone1level", "tone1tvfcutofffrequency", "level" })
            check(synth.mutationMap.isFree(key), key + " should start with mutation on");
        check(!synth.mutationMap.isFree("name"), "the patch name should never mutate");
        // Everything the manual marks <*> is ignored by the XV-5050, so never mutated.
        for (RolandXV5050Params.Block b : RolandXV5050Params.PATCH)
            for (RolandXV5050Params.P p : b.params)
                if (p.ignoredOnReceive)
                    check(m.getStatus(p.key) == Model.STATUS_IMMUTABLE,
                        p.key + " is marked <*> and should be immutable");
        }

    // ------------------------------------------------------------------ round trips

    /** The sysex of emitAll(..., toFile = true), concatenated. */
    static byte[] emitToFile(RolandXV5050 synth)
        {
        Object[] out = synth.emitAll(synth.getModel(), false, true);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (Object o : out)
            {
            check(o instanceof byte[], "emitAll(toFile) produced a " + o.getClass().getSimpleName());
            if (o instanceof byte[]) b.write((byte[])o, 0, ((byte[])o).length);
            }
        return b.toByteArray();
        }

    static void roundTripDefaults(RolandXV5050 synth) throws Exception
        {
        byte[] first = emitToFile(synth);
        check(first.length == 948 + 9 * RolandXV5050Sysex.DT1_OVERHEAD,
            "a patch file is 1056 bytes, found " + first.length);

        byte[][] messages = RolandXV5050Sysex.split(first);
        check(messages.length == 9, "a patch is nine messages, found " + messages.length);
        for (byte[] m : messages)
            {
            check(RolandXV5050Sysex.isDT1(m), "every message is a DT1");
            check(RolandXV5050Sysex.checksumOK(m), "every message checksums");
            for (int i = 1; i < m.length - 1; i++)
                check((m[i] & 0x80) == 0, "every byte between F0 and F7 is 7-bit");
            }

        check(synth.parse(first, true) == Synth.PARSE_SUCCEEDED, "the init patch parses");
        byte[] second = emitToFile(synth);
        check(Arrays.equals(first, second), "the init patch round-trips byte-identically");

        // The init resource on disk must be exactly what the editor emits for it.
        InputStream in = RolandXV5050.class.getResourceAsStream("RolandXV5050.init");
        if (in != null)
            {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            check(Arrays.equals(b.toByteArray(), first),
                "RolandXV5050.init matches what the editor emits for it");
            }
        else check(false, "RolandXV5050.init is missing from the package");
        }

    static void roundTripRandom(RolandXV5050 synth) throws Exception
        {
        Random random = new Random(20261114L);
        for (int trial = 0; trial < 12; trial++)
            {
            Model m = synth.getModel();
            for (RolandXV5050Params.Block b : RolandXV5050Params.PATCH)
                for (RolandXV5050Params.P p : b.params)
                    m.set(p.key, p.min + random.nextInt(p.max - p.min + 1));
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < RolandXV5050Params.NAME_LENGTH; i++)
                name.append((char)(32 + random.nextInt(95)));
            m.set("name", name.toString());

            Map<String, Integer> before = new HashMap<>();
            for (RolandXV5050Params.Block b : RolandXV5050Params.PATCH)
                for (RolandXV5050Params.P p : b.params)
                    before.put(p.key, m.get(p.key));
            String nameBefore = m.get("name", "");

            byte[] first = emitToFile(synth);
            check(synth.parse(first, true) == Synth.PARSE_SUCCEEDED, "a random patch parses (trial " + trial + ")");

            for (Map.Entry<String, Integer> e : before.entrySet())
                check(m.get(e.getKey(), Integer.MIN_VALUE) == e.getValue().intValue(),
                    "trial " + trial + ": " + e.getKey() + " changed across emit/parse ("
                    + e.getValue() + " -> " + m.get(e.getKey(), Integer.MIN_VALUE) + ")");
            check(nameBefore.equals(m.get("name", "")), "trial " + trial + ": the name survived");

            byte[] second = emitToFile(synth);
            check(Arrays.equals(first, second), "a random patch round-trips byte-identically (trial " + trial + ")");
            }
        synth.loadDefaults();
        }

    static void singleParameterEdits(RolandXV5050 synth)
        {
        Random random = new Random(5050L);
        for (RolandXV5050Params.Block b : RolandXV5050Params.PATCH)
            for (RolandXV5050Params.P p : b.params)
                {
                int value = p.min + random.nextInt(p.max - p.min + 1);
                synth.getModel().set(p.key, value);
                Object[] out = synth.emitAll(p.key);
                if (!check(out.length == 1, p.key + ": " + out.length + " messages for one edit")) continue;
                byte[] msg = (byte[])out[0];
                check(RolandXV5050Sysex.isDT1(msg), p.key + ": a live edit is a DT1");
                check(RolandXV5050Sysex.checksumOK(msg), p.key + ": a live edit checksums");
                check(msg.length - RolandXV5050Sysex.DT1_OVERHEAD == p.bytes,
                    p.key + ": a live edit carries all " + p.bytes + " byte(s)");
                int expected = RolandXV5050Sysex.TEMP_PATCH + (b.address << 7) + p.offset;
                check(RolandXV5050Sysex.addressOf(msg) == expected,
                    p.key + ": address " + Integer.toHexString(RolandXV5050Sysex.addressOf(msg))
                    + ", expected " + Integer.toHexString(expected));
                // Decode the message's payload back with the parameter's own rule.
                byte[] payload = new byte[p.offset + p.bytes];
                System.arraycopy(msg, RolandXV5050Sysex.ADDRESS_OFFSET + 4, payload, p.offset, p.bytes);
                check(RolandXV5050Sysex.decode(p, payload, 0) == value,
                    p.key + ": a live edit decodes back to " + value);
                }

        Object[] nameEdit = synth.emitAll("name");
        check(nameEdit.length == 1, "a name edit is one message");
        byte[] msg = (byte[])nameEdit[0];
        check(msg.length - RolandXV5050Sysex.DT1_OVERHEAD == RolandXV5050Params.NAME_LENGTH,
            "a name edit carries all twelve bytes");
        check(RolandXV5050Sysex.addressOf(msg) == RolandXV5050Sysex.TEMP_PATCH,
            "a name edit goes to the start of Common");
        check(synth.emitAll("bank").length == 0, "bank is not emittable");
        check(synth.emitAll("number").length == 0, "number is not emittable");
        synth.loadDefaults();
        }

    // ------------------------------------------------------------------ recognizer

    static void recognizer(RolandXV5050 synth)
        {
        byte[][] messages = RolandXV5050Sysex.split(emitToFile(synth));
        for (byte[] m : messages)
            check(RolandXV5050Rec.recognize(m), "the recognizer claims a patch block");
        check(RolandXV5050Rec.getNextSysexPatchGroup(messages, 0) == 9,
            "the recognizer consumes exactly nine messages, got "
            + RolandXV5050Rec.getNextSysexPatchGroup(messages, 0));

        // Two patches back to back.
        byte[][] two = new byte[18][];
        for (int i = 0; i < 9; i++) { two[i] = messages[i]; two[i + 9] = messages[i]; }
        check(RolandXV5050Rec.getNextSysexPatchGroup(two, 0) == 9, "the first of two patches is nine messages");
        check(RolandXV5050Rec.getNextSysexPatchGroup(two, 9) == 18, "the second of two patches is nine messages");

        // Eight blocks is not a patch.
        byte[][] short8 = new byte[8][];
        System.arraycopy(messages, 0, short8, 0, 8);
        check(RolandXV5050Rec.getNextSysexPatchGroup(short8, 0) == 0, "an incomplete patch is refused");

        // A foreign block at the same base is skipped, not fatal.
        byte[][] withExtra = new byte[10][];
        withExtra[0] = messages[0];
        withExtra[1] = RolandXV5050Sysex.dt1((byte)0x10,
            RolandXV5050Sysex.TEMP_PATCH + (0x30 << 7), new byte[7]);     // no such block
        System.arraycopy(messages, 1, withExtra, 2, 8);
        check(RolandXV5050Rec.getNextSysexPatchGroup(withExtra, 0) == 10,
            "an unknown block inside a patch is skipped");

        // Things the patch recognizer must not claim.
        check(!RolandXV5050Rec.recognize(RolandXV5050Sysex.dt1((byte)0x10,
            RolandXV5050Sysex.TEMP_PATCH, 42)), "a single-parameter DT1 is not a patch");
        check(!RolandXV5050Rec.recognize(RolandXV5050Sysex.dt1((byte)0x10,
            RolandXV5050Sysex.TEMP_RHYTHM, new byte[18])), "a rhythm Common block is not a patch");
        check(!RolandXV5050Rec.recognize(RolandXV5050Sysex.dt1((byte)0x10,
            RolandXV5050Sysex.TEMP_RHYTHM + (0x02 << 7), new byte[145])), "a rhythm MFX block is not a patch");
        check(!RolandXV5050Rec.recognize(RolandXV5050Sysex.dt1((byte)0x10,
            RolandXV5050Sysex.lin(0x40, 0x00, 0x00, 0x00), new byte[18])), "a user rhythm is not a patch");
        check(!RolandXV5050Rec.recognize(RolandXV5050Sysex.dt1((byte)0x10,
            RolandXV5050Sysex.TEMP_PERF, new byte[53])), "a performance Common block is not a patch");
        check(!RolandXV5050Rec.recognize(RolandXV5050Sysex.dt1((byte)0x10,
            RolandXV5050Sysex.USER_PERF, new byte[53])), "a user performance is not a patch");
        check(!RolandXV5050Rec.recognize(RolandXV5050Sysex.dt1((byte)0x10,
            RolandXV5050Sysex.SYSTEM_COMMON, new byte[41])), "System Common is not a patch");

        // Other manufacturers and malformed data.
        check(!RolandXV5050Rec.recognize(new byte[] { (byte)0xF0, 0x43, 0x00, (byte)0xF7 }), "Yamaha sysex is refused");
        check(!RolandXV5050Rec.recognize(new byte[] { (byte)0xF0, (byte)0xF7 }), "a two-byte message is refused");
        check(!RolandXV5050Rec.recognize(new byte[0]), "an empty message is refused");

        // A JV-880 patch uses model ID 46, not 00 10.
        byte[] jv = new byte[45];
        jv[0] = (byte)0xF0; jv[1] = 0x41; jv[2] = 0x10; jv[3] = 0x46; jv[4] = 0x12;
        jv[44] = (byte)0xF7;
        check(!RolandXV5050Rec.recognize(jv), "a JV-880 message is refused");

        // Every device ID the panel allows is accepted.
        for (int id = 0x10; id <= 0x1F; id++)
            check(RolandXV5050Rec.recognize(RolandXV5050Sysex.dt1((byte)id,
                RolandXV5050Sysex.TEMP_PATCH, new byte[79])), "device ID " + id + " is accepted");

        // Patches stored in user memory and in a performance part.
        check(RolandXV5050Rec.recognize(RolandXV5050Sysex.dt1((byte)0x10,
            RolandXV5050Sysex.userPatch(127), new byte[79])), "user patch 128's Common block is a patch");
        check(RolandXV5050Rec.recognize(RolandXV5050Sysex.dt1((byte)0x10,
            RolandXV5050Sysex.TEMP_PART + 15 * RolandXV5050Sysex.PART_STRIDE + (0x26 << 7), new byte[137])),
            "part 16's Tone 4 block is a patch");

        // Parsing a user-address patch sets the location.
        byte[][] user = new byte[9][];
        for (int i = 0; i < 9; i++)
            {
            RolandXV5050Params.Block b = RolandXV5050Params.PATCH[i];
            byte[] payload = new byte[b.size];
            System.arraycopy(messages[i], RolandXV5050Sysex.ADDRESS_OFFSET + 4, payload, 0, b.size);
            user[i] = RolandXV5050Sysex.dt1((byte)0x10,
                RolandXV5050Sysex.userPatch(42) + (b.address << 7), payload);
            }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] m : user) all.write(m, 0, m.length);
        check(synth.parse(all.toByteArray(), true) == Synth.PARSE_SUCCEEDED, "a user-address patch parses");
        check(synth.getModel().get("bank", -1) == RolandXV5050.BANK_USER, "a user-address patch lands in USER");
        check(synth.getModel().get("number", -1) == 42, "a user-address patch keeps its number");
        synth.loadDefaults();
        }

    // ------------------------------------------------------------------- snapshots

    /** Render every tab to a PNG, so the layout can be eyeballed without launching Edisyn. */
    static void snapshot(RolandXV5050 synth, Path dir) throws IOException
        {
        Files.createDirectories(dir);
        for (int i = 0; i < synth.tabs.getTabCount(); i++)
            {
            java.awt.Component at = synth.tabs.getComponentAt(i);
            javax.swing.JComponent view = (at instanceof javax.swing.JScrollPane)
                ? (javax.swing.JComponent)((javax.swing.JScrollPane)at).getViewport().getView()
                : (javax.swing.JComponent)at;
            java.awt.Dimension d = view.getPreferredSize();
            view.setSize(d);
            layout(view);
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                Math.max(1, d.width), Math.max(1, d.height), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(view.getBackground() == null ? java.awt.Color.DARK_GRAY : view.getBackground());
            g.fillRect(0, 0, d.width, d.height);
            view.printAll(g);
            g.dispose();
            String name = String.format("%02d_%s.png", i + 1,
                synth.tabs.getTitleAt(i).replaceAll("[^A-Za-z0-9]+", "_"));
            javax.imageio.ImageIO.write(img, "png", dir.resolve(name).toFile());
            System.out.println(name + ": " + d.width + " x " + d.height);
            }
        }

    static void layout(java.awt.Component c)
        {
        c.doLayout();
        if (c instanceof java.awt.Container)
            for (java.awt.Component child : ((java.awt.Container)c).getComponents())
                layout(child);
        }

    // ----------------------------------------------------------------------- files

    static void roundTripFiles(RolandXV5050 synth, Path dir) throws Exception
        {
        if (!Files.isDirectory(dir)) { System.out.println("no such directory: " + dir); return; }
        java.util.List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.syx"))
            { for (Path p : s) files.add(p); }
        Collections.sort(files);
        System.out.println("round-tripping " + files.size() + " file(s) from " + dir);
        for (Path f : files)
            {
            byte[] data = Files.readAllBytes(f);
            if (synth.parse(data, true) != Synth.PARSE_SUCCEEDED)
                { check(false, f.getFileName() + " did not parse"); continue; }
            byte[] again = emitToFile(synth);
            byte[][] original = RolandXV5050Sysex.split(data);
            ByteArrayOutputStream payloadA = new ByteArrayOutputStream();
            for (byte[] m : original)
                if (RolandXV5050Rec.recognize(m))
                    payloadA.write(m, RolandXV5050Sysex.ADDRESS_OFFSET + 4,
                        m.length - RolandXV5050Sysex.DT1_OVERHEAD);
            ByteArrayOutputStream payloadB = new ByteArrayOutputStream();
            for (byte[] m : RolandXV5050Sysex.split(again))
                payloadB.write(m, RolandXV5050Sysex.ADDRESS_OFFSET + 4,
                    m.length - RolandXV5050Sysex.DT1_OVERHEAD);
            // Compare the data only: the device ID and checksums are recomputed.
            check(Arrays.equals(payloadA.toByteArray(), payloadB.toByteArray()),
                f.getFileName() + " round-trips");
            }
        synth.loadDefaults();
        }
    }
