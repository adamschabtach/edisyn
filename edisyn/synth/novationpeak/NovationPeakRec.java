/***
    Copyright 2026 by Adam Schabtach
    Licensed under the Apache License version 2.0
*/

package edisyn.synth.novationpeak;
import edisyn.*;

/** Recognizes Novation Peak patch dumps: 527 bytes, header 00 20 29 01 10 00 7E, command 00 (edit buffer) or 01 (stored slot). */
public class NovationPeakRec extends Recognize
    {
    public static boolean recognize(byte[] data)
        {
        return NovationPeak.isPatchMessage(data);
        }
    }
