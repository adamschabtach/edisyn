/***
    Copyright 2026 by Adam Schabtach
    Licensed under the Apache License version 2.0
*/

package edisyn.synth.novationpeak;

import edisyn.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.sound.midi.*;

/**
   Headless tests for the Novation Peak editor. Not part of Edisyn's UI.

       java -Djava.awt.headless=true -cp libraries/coremidi4j-1.6.jar:. \
            edisyn.synth.novationpeak.PeakHarness /path/to/peak-editor

   Round trip over every Peak patch in peak-editor's captures/ and backups/, CC/NRPN output
   against tests/fixtures/edisyn/control_vectors.json (peak-editor's ControlGuard), the
   write guard, following the panel (incoming CC/NRPN), and the mutation policy. Exit status 0
   when everything passes.
*/

public class PeakHarness
    {
    static int failures = 0, checks = 0;

    static void check(boolean ok, String what)
        {
        checks++;
        if (!ok)
            {
            failures++;
            if (failures <= 40) System.out.println("FAIL: " + what);
            }
        }

    public static void main(String[] args)
        {
        try { run(args); }
        catch (Throwable t) { t.printStackTrace(System.out); System.out.println("PEAK HARNESS CRASHED"); System.exit(3); }
        }

    static void run(String[] args) throws Exception
        {
        if (args.length != 1 && !(args.length == 3 && args[1].equals("--snapshot")))
            { System.err.println("usage: PeakHarness <peak-editor dir> [--snapshot <png dir>]"); System.exit(2); }
        Path repo = Paths.get(args[0]);

        NovationPeak.testMode = true;
        NovationPeak synth = new NovationPeak();
        synth.setSendMIDI(false);
        synth.getUndo().setWillPush(false);

        if (args.length == 3) { snapshot(synth, Paths.get(args[2])); System.exit(0); }

        check(synth.getModel().get("name", "").startsWith("Init Patch"), "init patch loaded (name '" + synth.getModel().get("name", "") + "')");

        int patches = roundTrip(synth, repo);
        System.out.println("round trip: " + patches + " patch messages");
        int vectors = controlVectors(synth, repo.resolve("tests/fixtures/edisyn/control_vectors.json"));
        System.out.println("control vectors: " + vectors + " cases");
        writeGuard(synth);
        System.out.println("write guard: done");
        int incoming = incoming(synth);
        System.out.println("incoming: " + incoming + " panel moves followed");
        policy(synth, repo);
        System.out.println("policy: done");

        System.out.println((failures == 0 ? "PEAK HARNESS OK" : "PEAK HARNESS FAILED") + ": " + checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
        }

    // ------------------------------------------------------------------------------------
    /** Paints every tab offscreen to <dir>/<n>_<tab>.png (no window, no screen capture). */
    static void snapshot(NovationPeak synth, Path dir) throws IOException
        {
        Files.createDirectories(dir);
        for (int i = 0; i < synth.tabs.getTabCount(); i++)
            {
            javax.swing.JScrollPane pane = (javax.swing.JScrollPane)synth.tabs.getComponentAt(i);
            javax.swing.JComponent view = (javax.swing.JComponent)pane.getViewport().getView();
            java.awt.Dimension d = view.getPreferredSize();
            view.setSize(d);
            layout(view);
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(Math.max(1, d.width), Math.max(1, d.height), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(view.getBackground() == null ? java.awt.Color.DARK_GRAY : view.getBackground());
            g.fillRect(0, 0, d.width, d.height);
            view.printAll(g);
            g.dispose();
            String name = String.format("%02d_%s.png", i + 1, synth.tabs.getTitleAt(i).replaceAll("[^A-Za-z0-9]+", "_"));
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

    // ------------------------------------------------------------------------------------
    static List<byte[]> patchMessages(Path root) throws IOException
        {
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.walk(root))
            {
            s.filter(p -> p.toString().endsWith(".syx") && !p.toString().contains(File.separator + "waves" + File.separator))
                .sorted().forEach(files::add);
            }
        List<byte[]> out = new ArrayList<>();
        for (Path f : files)
            {
            byte[] d = Files.readAllBytes(f);
            for (int i = 0; i < d.length; )
                {
                if ((d[i] & 0xFF) != 0xF0) { i++; continue; }
                int j = i;
                while (j < d.length && (d[j] & 0xFF) != 0xF7) j++;
                if (j >= d.length) break;
                byte[] m = Arrays.copyOfRange(d, i, j + 1);
                i = j + 1;
                if (m.length == NovationPeak.PATCH_LENGTH && m.length > 8 && (m[8] == 0 || m[8] == 1) && NovationPeak.hasPeakHeader(m))
                    out.add(m);
                }
            }
        return out;
        }

    static int roundTrip(NovationPeak synth, Path repo) throws IOException
        {
        List<byte[]> msgs = new ArrayList<>();
        msgs.addAll(patchMessages(repo.resolve("captures")));
        msgs.addAll(patchMessages(repo.resolve("backups")));
        check(msgs.size() >= 1500, "found " + msgs.size() + " patch messages");

        int n = 0;
        for (byte[] m : msgs)
            {
            String where = (m[8] == 1 ? "slot " + NovationPeak.BANKS[m[12] - 1] + m[13] : "edit buffer") + " msg " + n;
            check(NovationPeakRec.recognize(m), "recognize " + where);
            check(synth.parse(m, true) == Synth.PARSE_SUCCEEDED, "parse " + where);
            byte[] back = m[8] == 0 ? synth.emit(null, true, false) : synth.emit(synth.getModel(), false, true);
            if (!Arrays.equals(m, back))
                {
                int first = -1;
                for (int i = 0; i < m.length && first < 0; i++) if (back == null || m[i] != back[i]) first = i;
                check(false, "round trip " + where + ": first difference at 0x" + Integer.toHexString(first));
                }
            else check(true, "");
            n++;
            }

        // Wrong lengths and foreign messages are refused.
        byte[] m = msgs.get(0);
        check(!NovationPeakRec.recognize(Arrays.copyOf(m, m.length - 1)), "short message not recognized");
        byte[] summit = m.clone();
        summit[5] = 0x11;
        check(synth.parse(summit, true) == Synth.PARSE_FAILED, "Summit header refused");
        byte[] wave = Arrays.copyOf(m, 531);
        wave[8] = 0x06;
        check(!NovationPeakRec.recognize(wave), "wave message not recognized");
        return n;
        }

    // ------------------------------------------------------------------------------------
    static int controlVectors(NovationPeak synth, Path file) throws IOException
        {
        Object root = new Json(new String(Files.readAllBytes(file), "UTF-8")).value();
        @SuppressWarnings("unchecked") List<Object> params = (List<Object>)((Map<String, Object>)root).get("params");
        Map<String, NovationPeakParams.P> byId = new HashMap<>();
        for (NovationPeakParams.P p : NovationPeakParams.PARAMS) byId.put(p.id, p);

        int cases = 0;
        for (Object o : params)
            {
            @SuppressWarnings("unchecked") Map<String, Object> jp = (Map<String, Object>)o;
            String id = (String)jp.get("id");
            NovationPeakParams.P p = byId.get(id);
            @SuppressWarnings("unchecked") List<Object> cs = (List<Object>)jp.get("cases");
            boolean sendable = false;
            for (Object co : cs) if (((Map<?, ?>)co).containsKey("messages")) sendable = true;

            if (p == null)
                {
                if (sendable) System.out.println("note: " + id + " is sendable but has no measured offset, so it is not editable");
                continue;
                }
            boolean live = p.route == NovationPeakParams.ROUTE_CC || p.route == NovationPeakParams.ROUTE_CC_PAIR || p.route == NovationPeakParams.ROUTE_NRPN;
            if (p.editable) check(live == sendable, id + ": route " + p.route + " but ControlGuard sendable=" + sendable);
            if (!live) continue;

            for (Object co : cs)
                {
                @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>)co;
                int v = ((Number)c.get("value")).intValue();
                synth.getModel().set(p.key, v);
                Object[] out = synth.emitAll(p.key);
                cases++;
                if (c.containsKey("messages"))
                    {
                    @SuppressWarnings("unchecked") List<Object> want = (List<Object>)c.get("messages");
                    boolean same = out.length == want.size();
                    for (int i = 0; same && i < out.length; i++)
                        {
                        byte[] got = ((ShortMessage)out[i]).getMessage();
                        @SuppressWarnings("unchecked") List<Object> w = (List<Object>)want.get(i);
                        for (int b = 0; b < 3; b++) same &= (got[b] & 0xFF) == ((Number)w.get(b)).intValue();
                        }
                    check(same, id + "=" + v + ": messages differ from ControlGuard");
                    }
                else
                    {
                    check(out.length == 0, id + "=" + v + ": refused by ControlGuard but emitted CC/NRPN");
                    }
                }
            }
        return cases;
        }

    // ------------------------------------------------------------------------------------
    static void writeGuard(NovationPeak synth) throws Exception
        {
        List<Object> sent = new ArrayList<>();
        synth.captureOutgoing = sent;
        NovationPeak.testDialogs.clear();

        // Edisyn's write paths.
        check(synth.emitAll(synth.getModel(), false, false).length == 0, "slot write produces nothing");
        check(synth.emit(synth.getModel(), false, false) == null, "slot write emit() is null");
        check(!synth.gatherPatchInfo("Write", synth.getModel(), true), "gatherPatchInfo(writing) refuses");
        synth.writeAllParameters(synth.getModel());
        check(NovationPeak.testDialogs.size() >= 2, "write refusal dialogs shown (" + NovationPeak.testDialogs.size() + ")");
        boolean[] writable = synth.getWriteableBanks();
        boolean anyWritable = false;
        for (boolean b : writable) anyWritable |= b;
        check(!anyWritable && !synth.getSupportsPatchWrites() && !synth.getSupportsBankWrites(), "librarian is read-only");

        // Everything a user can trigger: requests, send-to-current, every parameter, a debounced dump.
        synth.tryToSendSysex(synth.requestCurrentDump());
        Model loc = synth.buildModel();
        loc.set("bank", 3);
        loc.set("number", 127);
        synth.tryToSendSysex(synth.requestDump(loc));
        synth.changePatch(loc);
        synth.tryToSendMIDI(synth.emitAll(synth.getModel(), true, false));
        Random r = new Random(1);
        for (NovationPeakParams.P p : NovationPeakParams.PARAMS)
            {
            if (!p.editable) continue;
            synth.getModel().set(p.key, p.storeMin + r.nextInt(p.storeMax - p.storeMin + 1));
            synth.tryToSendMIDI(synth.emitAll(p.key));
            }
        synth.tryToSendMIDI(synth.emitAll("name"));
        synth.sendPendingDump();
        check(synth.refetchesScheduled > 0, "a coupled edit (Reverb Type/Size) schedules a re-read");
        if (synth.refetchTimer != null) synth.refetchTimer.stop();

        int dumps = 0, requests = 0, shorts = 0;
        for (Object o : sent)
            {
            if (o instanceof byte[])
                {
                byte[] d = (byte[])o;
                check(d[8] != 0x01, "a slot write reached MIDI");
                if (d[8] == 0x00) dumps++; else requests++;
                }
            else if (o instanceof ShortMessage)
                {
                ShortMessage s = (ShortMessage)o;
                shorts++;
                check(s.getCommand() != ShortMessage.PROGRAM_CHANGE, "program change reached MIDI");
                check(!(s.getCommand() == ShortMessage.CONTROL_CHANGE && (s.getData1() == 0 || s.getData1() == 32 || s.getData1() == 38)),
                    "CC " + s.getData1() + " reached MIDI");
                }
            }
        check(dumps == 2 && requests == 2 && shorts > 200, "outgoing: " + dumps + " dumps, " + requests + " requests, " + shorts + " CCs");

        // The guard itself refuses everything else.
        int before = synth.refused.size();
        byte[] slotWrite = synth.buildPatch(NovationPeak.CMD_STORED, 3, 127);
        check(!synth.tryToSendSysex(slotWrite), "guard refuses 0x01 slot write");
        check(!synth.tryToSendMIDI(new SysexMessage(slotWrite, slotWrite.length)), "guard refuses 0x01 as SysexMessage");
        byte[] wave = new byte[531];
        System.arraycopy(slotWrite, 0, wave, 0, 9);
        wave[8] = 0x06;
        wave[530] = (byte)0xF7;
        check(!synth.tryToSendSysex(wave), "guard refuses wave upload");
        byte[] bank = NovationPeak.request(0x4A, 1, 0);
        check(!synth.tryToSendSysex(bank), "guard refuses bank request");
        byte[] foreign = synth.requestCurrentDump();
        foreign[5] = 0x11;
        check(!synth.tryToSendSysex(foreign), "guard refuses another header");
        check(!synth.tryToSendSysex(NovationPeak.request(0x41, 5, 0)), "guard refuses bank 5");
        for (int cc : new int[] { 0, 32, 38, 96, 97, 100, 101, 121, 122, 124, 127 })
            check(!synth.tryToSendMIDI(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, cc, 1)), "guard refuses CC " + cc);
        check(!synth.tryToSendMIDI(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 5, 0)), "guard refuses program change");
        check(!synth.tryToSendMIDI(new ShortMessage(ShortMessage.PITCH_BEND, 0, 0, 64)), "guard refuses pitch bend");
        check(synth.refused.size() - before == 19, "guard logged " + (synth.refused.size() - before) + " refusals");
        check(synth.tryToSendMIDI(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)), "guard passes test notes");
        check(synth.tryToSendMIDI(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 123, 0)), "guard passes All Notes Off");

        synth.captureOutgoing = null;
        }

    // ------------------------------------------------------------------------------------
    static void feed(NovationPeak synth, int channel, int cc, int value)
        {
        synth.handleSynthCCOrNRPN(new Midi.CCData(Midi.CCDATA_TYPE_RAW_CC, cc, value, channel, false));
        }

    static void feed(NovationPeak synth, Object[] messages)
        {
        for (Object o : messages)
            {
            ShortMessage m = (ShortMessage)o;
            feed(synth, m.getChannel(), m.getData1(), m.getData2());
            }
        }

    /** The panel's messages, as the editor itself would send them (checked against ControlGuard
        by controlVectors), fed back in must set exactly that value; nothing is sent back. */
    static int incoming(NovationPeak synth) throws Exception
        {
        Model model = synth.getModel();
        List<Object> sent = new ArrayList<>();
        synth.captureOutgoing = sent;
        int followed = 0;

        // The incoming maps must not overlap each other or the NRPN controllers.
        Set<Integer> seen = new HashSet<>(Arrays.asList(99, 98, 6));
        for (Map<Integer, NovationPeakParams.P> m : Arrays.asList(NovationPeak.IN_CC, NovationPeak.IN_PAIR_MSB, NovationPeak.IN_PAIR_LSB))
            for (int cc : m.keySet()) check(seen.add(cc), "incoming CC " + cc + " is mapped twice");

        for (NovationPeakParams.P p : NovationPeakParams.PARAMS)
            {
            if (!p.editable) continue;
            boolean viaCC38 = p.route == NovationPeakParams.ROUTE_DUMP && p.cc >= 0;
            if (p.route != NovationPeakParams.ROUTE_CC && p.route != NovationPeakParams.ROUTE_CC_PAIR
                && p.route != NovationPeakParams.ROUTE_NRPN && !viaCC38) continue;

            int mid = (p.chartMin + p.chartMax) / 2;
            int[] values = { p.chartMin, p.chartMax, mid, mid + 1 < p.chartMax ? mid + 1 : mid, p.def };
            for (int v : values)
                {
                if (v < p.chartMin || v > p.chartMax) continue;
                int other = v == p.chartMin ? p.chartMax : p.chartMin;
                model.set(p.key, other);
                Object[] msgs = viaCC38
                    ? new Object[] { new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, p.cc, v) }
                    : NovationPeak.controlMessages(p, v, 0);
                check(msgs.length > 0, p.key + " = " + v + ": no messages to feed");
                feed(synth, msgs);
                check(model.get(p.key, -1) == v, p.key + ": panel sent " + v + ", editor shows " + model.get(p.key, -1));
                followed++;

                // The same move on another channel is ignored.
                model.set(p.key, other);
                for (Object o : msgs)
                    {
                    ShortMessage m = (ShortMessage)o;
                    feed(synth, 1, m.getData1(), m.getData2());
                    }
                check(model.get(p.key, -1) == other, p.key + ": a move on channel 2 was followed");
                }

            if (p.route == NovationPeakParams.ROUTE_CC_PAIR)
                {
                // MSB alone: applied at once, keeping the low bit.
                model.set(p.key, 1);
                feed(synth, 0, p.cc, 0x30);
                check(model.get(p.key, -1) == (0x30 << 1 | 1), p.key + ": MSB alone gives " + model.get(p.key, -1));
                // An LSB that doesn't directly follow its MSB is ignored.
                feed(synth, 0, 99, 0);
                feed(synth, 0, p.ccLsb, 0);
                check(model.get(p.key, -1) == (0x30 << 1 | 1), p.key + ": stray LSB changed the value");
                }
            }

        if (synth.refetchTimer != null) synth.refetchTimer.stop();

        // Reverb Type / Size are coupled: a move sets the value and re-reads the edit buffer once,
        // so the other one is picked up too.
        List<String> coupled = new ArrayList<>();
        for (NovationPeakParams.P p : NovationPeakParams.PARAMS) if (p.coupled) coupled.add(p.key);
        check(coupled.equals(Arrays.asList("reverbtype", "reverbsize")), "coupled parameters: " + coupled);
        for (String k : coupled)
            {
            NovationPeakParams.P p = NovationPeak.BY_KEY.get(k);
            check(p.editable && p.immutable && p.route == NovationPeakParams.ROUTE_NRPN, k + ": coupled but not an immutable NRPN control");
            model.set(k, p.chartMin);
            int scheduled = synth.refetchesScheduled;
            feed(synth, NovationPeak.controlMessages(p, p.chartMax, 0));
            check(model.get(k, -1) == p.chartMax, k + ": panel move not followed");
            check(synth.refetchesScheduled > scheduled && synth.refetchTimer.isRunning(), k + ": panel move doesn't schedule a re-read");
            check(sent.isEmpty(), k + ": a panel move sent " + sent.size() + " message(s) before the re-read");
            synth.sendRefetch();
            check(sent.size() == 1 && sent.get(0) instanceof byte[] && ((byte[])sent.get(0)).length == NovationPeak.REQUEST_LENGTH
                && ((byte[])sent.get(0))[8] == NovationPeak.CMD_REQUEST_EDIT, k + ": re-read sends exactly one 0x40 request");
            sent.clear();

            // Edited here: sent live as NRPN, then re-read.
            scheduled = synth.refetchesScheduled;
            model.set(k, p.chartMin);
            Object[] out = synth.emitAll(k);
            check(out.length == 3 && synth.refetchesScheduled > scheduled, k + ": an edit here isn't sent live and re-read");
            synth.refetchTimer.stop();
            }

        // Unknown CCs, Animate Hold (no stored byte) and NRPNs for global settings are ignored.
        sent.clear();
        Model snap = model.copy();
        feed(synth, 0, 114, 127);           // Animate 1 Hold
        feed(synth, 0, 99, 64); feed(synth, 0, 98, 3); feed(synth, 0, 6, 10);   // Aftertouch Scale (global)
        for (String k : model.getKeys()) check(model.get(k, 0) == snap.get(k, 0), "ignored message changed " + k);
        check(sent.isEmpty() && (synth.refetchTimer == null || !synth.refetchTimer.isRunning()), "ignored message sent something");

        synth.captureOutgoing = null;
        return followed;
        }

    // ------------------------------------------------------------------------------------
    static void policy(NovationPeak synth, Path repo) throws IOException
        {
        // Work on detached model copies: the synth's own model drives widgets, and thousands of
        // updates would flood the Swing event queue.
        List<byte[]> msgs = patchMessages(repo.resolve("backups"));
        Random r = new Random(42);
        List<Model> pool = new ArrayList<>();
        for (int i = 0; i < 200; i++)
            {
            synth.parse(msgs.get(r.nextInt(msgs.size())), true);
            pool.add(synth.getModel().copy());
            }
        String[] mutationKeys = synth.getMutationKeys();
        Set<String> mutable = new HashSet<>(Arrays.asList(mutationKeys));

        // Defaults: immutables and default exclusions are not mutation keys.
        for (NovationPeakParams.P p : NovationPeakParams.PARAMS)
            {
            if (!p.editable || p.immutable) check(!mutable.contains(p.key), p.key + " is immutable but mutable");
            if (p.excludedByDefault) check(!mutable.contains(p.key), p.key + " should be excluded from mutation by default");
            }
        check(!mutable.contains("name"), "name not mutable");
        check(pool.get(0) instanceof NovationPeak.PeakModel, "model copies keep the Peak mutation hook");
        for (String k : mutationKeys) check(!k.startsWith("reserved") && !k.endsWith("lsbextra"), k + " is a hidden key but mutable");

        String[] ops = { "mutate", "recombine", "crossover", "opposite", "morph" };
        for (String op : ops)
            {
            int changed = 0;
            for (int round = 0; round < 1000; round++)
                {
                Model before = pool.get(r.nextInt(pool.size()));
                Model other = pool.get(r.nextInt(pool.size()));
                Model m = before.copy();

                switch (op)
                    {
                    case "mutate":    m.mutate(r, mutationKeys, 1.0); break;
                    case "recombine": m.recombine(r, other, mutationKeys, 1.0); break;
                    case "crossover": m.crossover(r, other, mutationKeys, 0.5); break;
                    case "opposite":  m.opposite(r, other, mutationKeys, 1.0, true); break;
                    default:
                        m.morph(r, new Model[] { before, other, before.copy(), other.copy() }, before, mutationKeys,
                            new double[] { r.nextDouble(), r.nextDouble(), r.nextDouble(), r.nextDouble() },
                            new double[] { 0.5, 0.5, 0.5, 0.5 }, Model.CATEGORICAL_STRATEGY_STRONGEST);
                    }

                for (NovationPeakParams.P p : NovationPeakParams.PARAMS)
                    {
                    int was = before.get(p.key, 0), now = m.get(p.key, 0);
                    if (!mutable.contains(p.key))
                        check(was == now, op + ": " + p.key + " is not mutable but changed " + was + " -> " + now);
                    else if (was != now && ++changed > 0)
                        check(now >= p.uiMin && now <= p.uiMax, op + ": " + p.key + " = " + now + " outside " + p.uiMin + ".." + p.uiMax);
                    }
                // Route locks: a slot that points at a locked destination with a non-neutral depth
                // after the operation must be exactly as it was (no pitch routing created or changed),
                // and one that did so before must still do so.
                for (int g = 0; g < NovationPeakParams.GUARD_KINDS.length; g++)
                    {
                    if (NovationPeakParams.GUARD_KINDS[g] != NovationPeakParams.GUARD_ROUTE_LOCK) continue;
                    String[] keys = NovationPeakParams.GUARD_KEYS[g];
                    int[] locked = NovationPeakParams.GUARD_VALUES[g];
                    int neutral = NovationPeakParams.GUARD_LIMITS[g];
                    boolean liveBefore = NovationPeak.PeakModel.contains(locked, before.get(keys[1], -1)) && before.get(keys[0], 0) != neutral;
                    boolean liveAfter = NovationPeak.PeakModel.contains(locked, m.get(keys[1], -1)) && m.get(keys[0], 0) != neutral;
                    if (liveBefore || liveAfter)
                        for (String k : keys)
                            check(before.get(k, 0) == m.get(k, 0), op + ": locked routing " + keys[1] + " changed via " + k
                                + " " + before.get(k, 0) + " -> " + m.get(k, 0));
                    }
                check(m.get("name", "").equals(before.get("name", "")), op + ": name changed");
                for (int off : NovationPeakParams.HIDDEN_OFFSETS)
                    check(m.get(NovationPeak.hiddenKey(off), 0) == before.get(NovationPeak.hiddenKey(off), 0), op + ": hidden byte changed");
                }
            System.out.println("  " + op + ": " + changed + " values changed in 1000 rounds");
            check(changed > 1000, op + " changed almost nothing");
            }
        }

    // ------------------------------------------------------------------------------------
    /** Minimal JSON reader (objects, arrays, strings, numbers, true/false/null) for the fixture. */
    static final class Json
        {
        final String s;
        int i = 0;
        Json(String s) { this.s = s; }

        Object value()
            {
            ws();
            char c = s.charAt(i);
            if (c == '{')
                {
                Map<String, Object> m = new LinkedHashMap<>();
                i++; ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true)
                    {
                    ws(); String k = (String)value(); ws(); expect(':');
                    m.put(k, value()); ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}'); return m;
                    }
                }
            if (c == '[')
                {
                List<Object> l = new ArrayList<>();
                i++; ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true)
                    {
                    l.add(value()); ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect(']'); return l;
                    }
                }
            if (c == '"')
                {
                StringBuilder b = new StringBuilder();
                i++;
                while (s.charAt(i) != '"')
                    {
                    char ch = s.charAt(i++);
                    if (ch == '\\')
                        {
                        char e = s.charAt(i++);
                        if (e == 'u') { b.append((char)Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        else b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
                        }
                    else b.append(ch);
                    }
                i++;
                return b.toString();
                }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(start, i));
            }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        void expect(char c) { if (s.charAt(i) != c) throw new IllegalStateException("JSON: expected " + c + " at " + i); i++; }
        }
    }
