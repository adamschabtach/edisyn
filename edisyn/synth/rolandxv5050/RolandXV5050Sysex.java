/***
    Copyright 2026 by Adam Schabtach
    Licensed under the Apache License version 2.0
*/

package edisyn.synth.rolandxv5050;

/**
   Roland XV-5050 system-exclusive helpers: address arithmetic, DT1/RQ1 builders,
   checksums and the nibbled-value encoding. Shared by the Patch editor and (later)
   the Rhythm and Performance editors.

   <p>The XV-5050 uses Roland's model ID <tt>00 10</tt>, shared with the XV-3080 and
   XV-5080; the identity reply's family number <tt>02 02</tt> is what distinguishes it.

   <pre>
   RQ1: F0 41 dev 00 10 11 a1 a2 a3 a4 s1 s2 s3 s4 sum F7
   DT1: F0 41 dev 00 10 12 a1 a2 a3 a4 data... sum F7
   </pre>

   <p>Addresses and sizes are <b>7-bit</b>: every byte carries seven bits, so
   <tt>00 00 01 09</tt> is 137, not 265. All arithmetic here is done on the "linear"
   form <tt>a1&lt;&lt;21 | a2&lt;&lt;14 | a3&lt;&lt;7 | a4</tt> and converted back at
   the last moment.

   @author Adam Schabtach
*/

final class RolandXV5050Sysex
    {
    private RolandXV5050Sysex() { }

    static final byte SYSEX_START = (byte)0xF0;
    static final byte SYSEX_END = (byte)0xF7;
    static final byte ROLAND = (byte)0x41;
    static final byte MODEL_HI = (byte)0x00, MODEL_LO = (byte)0x10;
    static final byte RQ1 = (byte)0x11, DT1 = (byte)0x12;

    /** Index of the first address byte in a DT1 or RQ1. */
    static final int ADDRESS_OFFSET = 6;
    /** Bytes of a DT1 that are not data: F0 41 dev 00 10 12 a1 a2 a3 a4 ... sum F7. */
    static final int DT1_OVERHEAD = 12;
    /** An RQ1 is always this long. */
    static final int RQ1_LENGTH = 16;

    // Base addresses, in linear form.
    static final int SETUP          = lin(0x01, 0x00, 0x00, 0x00);
    static final int SYSTEM_COMMON  = lin(0x02, 0x00, 0x00, 0x00);
    static final int TEMP_PERF      = lin(0x10, 0x00, 0x00, 0x00);
    static final int TEMP_PART      = lin(0x11, 0x00, 0x00, 0x00);
    static final int TEMP_PATCH     = lin(0x1F, 0x00, 0x00, 0x00);
    static final int TEMP_RHYTHM    = lin(0x1F, 0x10, 0x00, 0x00);
    static final int USER_PERF      = lin(0x20, 0x00, 0x00, 0x00);
    static final int USER_PATCH     = lin(0x30, 0x00, 0x00, 0x00);

    /** Distance between consecutive Performance-mode part temporary patches. */
    static final int PART_STRIDE    = lin(0x00, 0x20, 0x00, 0x00);
    /** Distance between consecutive user patches. */
    static final int USER_STRIDE    = lin(0x00, 0x01, 0x00, 0x00);

    /** Sound Mode values written to {@link #SETUP}. */
    static final int MODE_PATCH = 0, MODE_PERFORM = 1;

    // ------------------------------------------------------------------ addresses

    /** The linear form of a four-byte 7-bit address. */
    static int lin(int a1, int a2, int a3, int a4)
        {
        return (a1 << 21) | (a2 << 14) | (a3 << 7) | a4;
        }

    /** The four address bytes of a linear address, most significant first. */
    static byte[] address(int linear)
        {
        return new byte[]
            {
            (byte)((linear >> 21) & 0x7F),
            (byte)((linear >> 14) & 0x7F),
            (byte)((linear >> 7) & 0x7F),
            (byte)(linear & 0x7F)
            };
        }

    /** The linear address of a DT1 or RQ1. Assumes the message is long enough. */
    static int addressOf(byte[] data)
        {
        return lin(data[ADDRESS_OFFSET] & 0x7F, data[ADDRESS_OFFSET + 1] & 0x7F,
                   data[ADDRESS_OFFSET + 2] & 0x7F, data[ADDRESS_OFFSET + 3] & 0x7F);
        }

    /** The linear base address of user patch 0..127. */
    static int userPatch(int number)
        {
        return USER_PATCH + number * USER_STRIDE;
        }

    // ------------------------------------------------------------------- messages

    /** Roland's checksum: the value that makes the address and data sum to 0 mod 128. */
    static byte checksum(byte[] data, int start, int end)
        {
        int sum = 0;
        for (int i = start; i < end; i++) sum += data[i];
        sum = sum & 0x7F;
        int check = 0x80 - sum;
        return (byte)(check == 0x80 ? 0 : check);
        }

    /** A DT1 writing <tt>data</tt> at <tt>linear</tt>. */
    static byte[] dt1(byte id, int linear, byte[] data)
        {
        byte[] out = new byte[DT1_OVERHEAD + data.length];
        byte[] a = address(linear);
        out[0] = SYSEX_START;
        out[1] = ROLAND;
        out[2] = id;
        out[3] = MODEL_HI;
        out[4] = MODEL_LO;
        out[5] = DT1;
        System.arraycopy(a, 0, out, ADDRESS_OFFSET, 4);
        System.arraycopy(data, 0, out, ADDRESS_OFFSET + 4, data.length);
        out[out.length - 2] = checksum(out, ADDRESS_OFFSET, out.length - 2);
        out[out.length - 1] = SYSEX_END;
        return out;
        }

    /** A DT1 writing a single byte -- used for the Sound Mode switch. */
    static byte[] dt1(byte id, int linear, int value)
        {
        return dt1(id, linear, new byte[] { (byte)(value & 0x7F) });
        }

    /** An RQ1 asking for <tt>size</tt> bytes (as a 7-bit span) at <tt>linear</tt>. */
    static byte[] rq1(byte id, int linear, int size)
        {
        byte[] out = new byte[RQ1_LENGTH];
        out[0] = SYSEX_START;
        out[1] = ROLAND;
        out[2] = id;
        out[3] = MODEL_HI;
        out[4] = MODEL_LO;
        out[5] = RQ1;
        System.arraycopy(address(linear), 0, out, ADDRESS_OFFSET, 4);
        System.arraycopy(address(size), 0, out, ADDRESS_OFFSET + 4, 4);
        out[14] = checksum(out, ADDRESS_OFFSET, 14);
        out[15] = SYSEX_END;
        return out;
        }

    /** Whether this looks like an XV-5050 family DT1 with at least one data byte. */
    static boolean isDT1(byte[] data)
        {
        return data != null &&
            data.length >= DT1_OVERHEAD + 1 &&
            data[0] == SYSEX_START &&
            data[1] == ROLAND &&
            (data[2] & 0xF0) == 0x10 &&                 // device ID 0x10..0x1F
            data[3] == MODEL_HI &&
            data[4] == MODEL_LO &&
            data[5] == DT1 &&
            data[data.length - 1] == SYSEX_END;
        }

    /** Whether the checksum of a DT1 is correct. */
    static boolean checksumOK(byte[] data)
        {
        return data[data.length - 2] == checksum(data, ADDRESS_OFFSET, data.length - 2);
        }

    // --------------------------------------------------------------------- values

    /** Split a value into <tt>count</tt> nibbles, most significant first. */
    static void putNibbled(byte[] out, int offset, int value, int count)
        {
        for (int i = 0; i < count; i++)
            out[offset + i] = (byte)((value >> (4 * (count - 1 - i))) & 0x0F);
        }

    /** Reassemble a nibbled value of <tt>count</tt> bytes. */
    static int getNibbled(byte[] data, int offset, int count)
        {
        int v = 0;
        for (int i = 0; i < count; i++)
            v = (v << 4) | (data[offset + i] & 0x0F);
        return v;
        }

    /** The wire bytes of one parameter's value. */
    static byte[] encode(RolandXV5050Params.P p, int value)
        {
        byte[] out = new byte[p.bytes];
        if (p.nibbled)
            putNibbled(out, 0, value, p.bytes);
        else
            out[0] = (byte)(value & 0x7F);
        return out;
        }

    /** One parameter's value, read from <tt>data</tt> at <tt>base + p.offset</tt>. */
    static int decode(RolandXV5050Params.P p, byte[] data, int base)
        {
        if (p.nibbled)
            return getNibbled(data, base + p.offset, p.bytes);
        return data[base + p.offset] & 0x7F;
        }

    // ------------------------------------------------------------------ splitting

    /**
       Split a buffer that may hold several concatenated sysex messages.
       Returns one byte[] per F0...F7 run; bytes outside a run are ignored.
    */
    static byte[][] split(byte[] data)
        {
        java.util.ArrayList<byte[]> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < data.length)
            {
            if (data[i] != SYSEX_START) { i++; continue; }
            int end = i + 1;
            while (end < data.length && data[end] != SYSEX_END) end++;
            if (end >= data.length) break;                      // unterminated: drop it
            byte[] msg = new byte[end - i + 1];
            System.arraycopy(data, i, msg, 0, msg.length);
            out.add(msg);
            i = end + 1;
            }
        return out.toArray(new byte[0][]);
        }
    }
