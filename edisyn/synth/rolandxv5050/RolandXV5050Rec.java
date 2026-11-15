/***
    Copyright 2026 by Adam Schabtach
    Licensed under the Apache License version 2.0
*/

package edisyn.synth.rolandxv5050;

import edisyn.*;

/**
   Recognizer for Roland XV-5050 patch sysex.

   <p>A patch is nine DT1 messages: Common, MFX, Chorus, Reverb, TMT and four Tones, at
   the Patch-mode temporary patch (<tt>1F 00 00 00</tt>), one of the 128 user patches
   (<tt>30 nn 00 00</tt>) or a Performance-mode part temporary patch
   (<tt>11 00 00 00</tt> + <tt>00 20 00 00</tt> per part). A message counts as a patch
   block only when its address is a block start for one of those bases <i>and</i> its
   data length is that block's size, so single-parameter DT1s from Tx Edit Data are
   not mistaken for dumps.

   <p>Rhythm (<tt>1F 10</tt>, <tt>40 n0</tt>) and Performance (<tt>10</tt>, <tt>20</tt>)
   messages are deliberately not claimed; those editors get their own recognizers.

   <p>{@link #recognize} has to answer for a single block <i>and</i> for all nine
   concatenated into one buffer, because that is the form the librarian asks about.
   See {@link #recognizeWholePatch}.
*/

public class RolandXV5050Rec extends Recognize
    {
    public static boolean recognize(byte[] data)
        {
        if (blockIndex(data) >= 0) return true;         // one block, as a file's messages arrive
        return recognizeWholePatch(data);
        }

    /**
       Whether a buffer is a whole patch as one run of concatenated DT1 messages.

       <p>Edisyn hands the librarian a patch through <tt>sendToLibrarian()</tt>, which
       flattens <tt>emitAll()</tt> into a single byte array before asking the
       recognizers about it. For a synth whose patch is one message that is the same
       thing; ours is nine, so the flattened buffer is not itself a block and has to be
       split before it can be recognized. Without this, every librarian download landed
       in the library with no synth attached.

       <p>The claim is deliberately tight, because the only thing that sends a buffer
       this way is a whole patch: every message in it must be a patch block of the
       <i>same</i> patch, each of the nine exactly once, and nothing else. Files are
       recognized a message at a time instead, through {@link #recognize} and
       {@link #getNextSysexPatchGroup}, which stay lenient about extra blocks.
    */
    static boolean recognizeWholePatch(byte[] data)
        {
        byte[][] messages = RolandXV5050Sysex.split(data);
        if (messages.length < 2) return false;          // a lone message went through blockIndex()

        int patchBase = base(messages[0]);
        if (patchBase < 0) return false;

        int seen = 0;
        for (byte[] message : messages)
            {
            int index = blockIndex(message);
            if (index < 0) return false;                // something that is not a patch block
            if (base(message) != patchBase) return false;
            if ((seen & (1 << index)) != 0) return false;
            seen |= 1 << index;
            }
        return seen == (1 << RolandXV5050Params.PATCH.length) - 1;
        }

    /** The patch block index of a message, or -1 if it is not an XV-5050 patch block. */
    static int blockIndex(byte[] data)
        {
        if (!RolandXV5050Sysex.isDT1(data)) return -1;
        int address = RolandXV5050Sysex.addressOf(data);
        int size = data.length - RolandXV5050Sysex.DT1_OVERHEAD;
        int base = RolandXV5050.patchBaseOf(address, size);
        if (base < 0) return -1;
        return RolandXV5050.blockIndexOf(address - base, size);
        }

    /** The patch base address of a message, or -1. */
    static int base(byte[] data)
        {
        if (!RolandXV5050Sysex.isDT1(data)) return -1;
        return RolandXV5050.patchBaseOf(RolandXV5050Sysex.addressOf(data),
                                        data.length - RolandXV5050Sysex.DT1_OVERHEAD);
        }

    /**
       Consume the nine blocks of one patch. The Common block comes first; the other
       eight may arrive in any order. Messages at the same base that are not patch
       blocks -- the extra blocks an XV-5080 file or another editor may carry -- are
       skipped rather than treated as a failure.
    */
    public static int getNextSysexPatchGroup(byte[][] sysex, int start)
        {
        if (start < 0 || start >= sysex.length) return start;

        int first = blockIndex(sysex[start]);
        if (first != 0)
            {
            System.err.println("RolandXV5050Rec.getNextSysexPatchGroup(): no Common block at " + start + ".");
            return start;
            }
        int patchBase = base(sysex[start]);

        int seen = 1;                                   // bit 0: Common
        final int all = (1 << RolandXV5050Params.PATCH.length) - 1;

        for (int i = start + 1; i < sysex.length; i++)
            {
            if (!RolandXV5050Sysex.isDT1(sysex[i])) break;
            int b = base(sysex[i]);
            int index = blockIndex(sysex[i]);
            if (index < 0)
                {
                // Not a block we model. If it belongs to some other object, the patch
                // ends here; if we cannot tell, skip it and keep looking.
                if (b >= 0 && b != patchBase) break;
                continue;
                }
            if (b != patchBase) break;                  // the next patch has started
            if ((seen & (1 << index)) != 0) break;      // a repeat: the next patch has started
            seen |= (1 << index);
            if (seen == all) return i + 1;
            }

        System.err.println("RolandXV5050Rec.getNextSysexPatchGroup(): incomplete patch at " + start
            + " (blocks 0x" + Integer.toHexString(seen) + " of 0x" + Integer.toHexString(all) + ").");
        return start;
        }
    }
