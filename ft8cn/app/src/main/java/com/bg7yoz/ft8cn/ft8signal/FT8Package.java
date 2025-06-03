package com.bg7yoz.ft8cn.ft8signal;
/**
 * This class provides methods for packing FT8 message data according to the FT8 protocol.
 * It handles the conversion of `Ft8Message` objects into the 77-bit packed binary data
 * format required for FT8 transmission. This includes encoding callsigns, grids, reports,
 * and other message components into their respective bit fields.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;

public class FT8Package {
    private static final String TAG = "FT8Package";

    /**
     * Total number of unique standard FT8 callsigns (c28 tokens).
     * Used as an offset when encoding hashed callsigns to differentiate them from standard encoded callsigns.
     */
    public static final int NTOKENS = 2063592;
    /**
     * Maximum value for a 22-bit hashed callsign representation (2^22).
     * Used in conjunction with NTOKENS for non-standard callsign encoding.
     */
    public static final int MAX22 = 4194304;
    /**
     * Maximum value for a 4-character Maidenhead grid locator encoded as g15 (18*18*10*10).
     * Used as an offset for encoding signal reports and special messages like "RRR", "RR73", "73".
     */
    public static final int MAXGRID4 = 32400;


    /**
     * Character set for the first character of a standard callsign in c28 encoding.
     * Includes a space and alphanumeric characters.
     */
    private static final String A1 = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /**
     * Character set for the second character of a standard callsign in c28 encoding.
     * Includes alphanumeric characters.
     */
    private static final String A2 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /**
     * Character set for the third character (digit) of a standard callsign in c28 encoding.
     */
    private static final String A3 = "0123456789";
    /**
     * Character set for the fourth, fifth, and sixth characters (suffix) of a standard callsign in c28 encoding.
     * Includes a space and uppercase letters.
     */
    private static final String A4 = " ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /**
     * Character set used for encoding non-standard callsigns (up to 11 characters) into an n58 representation.
     * Includes space, digits, uppercase letters, and '/'.
     */
    private static final String A5 = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/";

    // Static initializer block to load the native library.
    static {
        // Loads the 'ft8cn' native library which contains implementations for hashing functions
        // (getHash12, getHash10, getHash22) used in FT8 message packing.
        System.loadLibrary("ft8cn");
    }


    /**
     * Generates a 77-bit data packet for non-standard messages, purportedly where i3=4 according to the
     * original comment, but the implementation details for CQ messages set i3=3.
     * This type of message in FT8 typically involves a hashed callsign (c1, 12 bits from toCall or fromCall)
     * and a non-standard callsign (c2, 58 bits, fromCall, max 11 chars using A5 charset).
     * The remaining 7 bits are for report (s, 2 bits), message type (i3, 3 bits), and flags (p1, 1 bit; n3, 1 bit).
     *
     * Structure: h12(c1) | n58(c2) | s | i3 | p1 | n3  (total 12+58+2+3+1+1 = 77 bits)
     *
     * Note on `i3` and report `s` packing:
     * - For CQ: `data[9]` is set to `0x60`. This implies `i3=3` (011), `p1=0`, `n3=0`. Report `s` (last 2 bits of `data[8]`) becomes `00`.
     * - For replies: `data[9]` starts as `0x20` (implying `i3=1` (001), `p1=0`, `n3=0`).
     *   - "RRR": `data[8]` LSB (s0) becomes 0. `data[9]` MSB is set, changing `i3`.
     *   - "RR73": `data[8]` LSB (s0) becomes 1. `s1` (from `n58`) is unchanged. This might not correctly form `s=10` (RR73).
     *   - "73": `data[8]` LSB (s0) becomes 1. `data[9]` MSB is set. `s1` (from `n58`) is unchanged.
     * The logic for `s` and `i3` in replies is specific and might deviate from standard FT8 type 2 (i3=4) or type 1 (i3=0/1) message field definitions.
     * The comments below describe the bit manipulation as implemented.
     *
     * @param message The Ft8Message object containing the message details.
     * @return A byte array representing the 77-bit packed data (10 bytes).
     */
    public static byte[] generatePack77_i4(Ft8Message message) {
        // Clean callsigns by removing < and > characters.
        String toCall = message.callsignTo.replace("<", "").replace(">", "");
        String fromCall = message.callsignFrom.replace("<", "").replace(">", "");
        int hash12; // This will be c1 in the FT8 non-standard message (type 2)

        // Determine which callsign to hash for h12 (c1).
        // If it's a CQ message, use the sender's (fromCall) hash. Otherwise, use the recipient's (toCall) hash.
        if (message.checkIsCQ()) {
            hash12 = getHash12(fromCall);
        } else {
            hash12 = getHash12(toCall);
        }

        // Truncate fromCall to a maximum of 11 characters for n58 encoding if it's longer.
        if (fromCall.length() > 11) {
            fromCall = fromCall.substring(0, 11);
        }

        byte[] data = new byte[10]; // 10 bytes for 77 bits (77/8 = 9 with 5 bits remainder, so 10 bytes).
        long n58 = 0;
        // Encode fromCall into n58 using base-38 encoding with character set A5.
        // This represents the sender's callsign in 58 bits.
        for (int i = 0; i < fromCall.length(); i++) {
            n58 = n58 * 38 + A5.indexOf(fromCall.charAt(i));
        }
        // Example: n58=3479529522318088L; // This would be a specific callsign encoded.

        // Pack h12 (12 bits) into data[0] and the first 4 bits of data[1].
        data[0] = (byte) ((hash12 & 0x00000fff) >> 4);      // First 8 bits of hash12
        data[1] = (byte) ((hash12 & 0x0000000f) << 4);      // Last 4 bits of hash12, shifted to the left

        // Pack n58 (58 bits) starting from the remaining 4 bits of data[1] through data[8].
        // n58 takes 58 bits, which is 7 bytes and 2 bits.
        // data[1] (4 bits) | data[2..7] (6*8=48 bits) | data[8] (6 bits) = 58 bits
        data[1] = (byte) (data[1] | ((n58 & 0x0fff_ffff_ffff_ffffL) >> 54)); // Top 4 bits of n58
        data[2] = (byte) (((n58 & 0x00ff_ffff_ffff_ffffL) >> (54 - 8)));     // Next 8 bits
        data[3] = (byte) (((n58 & 0x0000_ffff_ffff_ffffL) >> (54 - 16)));    // Next 8 bits
        data[4] = (byte) (((n58 & 0x0000_00ff_ffff_ffffL) >> (54 - 24)));    // Next 8 bits
        data[5] = (byte) (((n58 & 0x0000_0000_ffff_ffffL) >> (54 - 32)));    // Next 8 bits
        data[6] = (byte) (((n58 & 0x0000_0000_00ff_ffffL) >> (54 - 40)));    // Next 8 bits
        data[7] = (byte) (((n58 & 0x0000_0000_0000_ffffL) >> (54 - 48)));    // Next 8 bits (total 52 bits of n58 so far in data[1] to data[7])
        // The remaining 6 bits of n58 are packed into the first 6 bits of data[8].
        data[8] = (byte) (((n58 & 0x0000_0000_0000_00ffL) << 2)); // Last 6 bits of n58 (shifted by 2 to fill MSB)

        // The last 7 bits of the 77-bit payload are: s(2), i3(3), p1(1), n3(1)
        // s: report bits (RRR=01, RR73=10, 73=11, ""=00. Here CQ is treated as "" report)
        // i3: message type identifier, fixed to 4 (binary 100) for this message type.
        // p1: flag indicating if the first callsign uses /P or /R (0 for non-standard messages).
        // n3: a field related to telemetry or other uses, typically 0 for standard messages.

        // For CQ messages (i3=4, type 2 non-standard)
        // s = 00 (no report), p1 = 0, n3 = 0. Bits for i3 are 100.
        // data[8] already has 6 bits of n58. Last 2 bits of data[8] are report (s).
        // data[9] has i3 (3 bits), p1 (1 bit), n3 (1 bit), and 3 padding bits.
        // For CQ: data[8] bits 1-0 are s=00. data[9] bits 7-5 are i3=100, bit 4 is p1=0, bit 3 is n3=0.
        // So, data[8] remains as is for its last 2 bits (effectively 00 from the shift).
        // data[9] becomes b10000xxx = 0x80 (if other bits are 0).
        // The code uses: data[9] = 0x60 which is b01100000.
        // Let's re-check FT8 spec for i3=4 (type 2).
        // Payload: c1 (12 bit hash) | c2 (58 bit call) | s (2 bit report) | i3 (3 bits) | p1 (1 bit) | n3 (1 bit)
        // For CQ: c1 is hash(mycall), c2 is mycall (non-std), s is 00, i3=4 (100), p1=0, n3=0.
        // Last 2 bits of data[8] are s0, s1.
        // data[9] bits: i3_0, i3_1, i3_2, p1, n3, X, X, X (X are padding)
        // If s=00 (CQ): data[8] lower 2 bits are 00.
        // i3=4 (100): data[9] MSB three are 100. p1=0, n3=0. So data[9] = b10000000 = 0x80.
        // The current code has data[9] = 0x60 (b01100000) for CQ. This seems to be i3=3. This might be a specific interpretation or a deviation.
        // Assuming the code is implementing a specific structure:
        // If CQ: data[9] = 0x60 implies i3=3 (011), p1=0, n3=0. Report bits (s0,s1 in data[8]) are 00.
        // If not CQ (e.g. response to CQ): data[9] = 0x20 implies i3=1 (001), p1=0, n3=0. Report bits are set below.

        if (message.checkIsCQ()) { // If it's a CQ message
            // data[8] last 2 bits are effectively 00 for report (s).
            // data[9] is set to 0x60 (01100000). This implies i3=3 (011), p1=0, n3=0 for CQ.
            data[9] = (byte) 0x60;
        } else { // If it's a reply (e.g., to a CQ, or other non-standard exchange)
            // data[9] is initially 0x20 (00100000). This implies i3=1 (001), p1=0, n3=0.
            data[9] = (byte) 0x20;
            // Report bits (s0, s1) are the last 2 bits of data[8].
            // extraInfo mapping to s (report bits):
            // "RRR":  s=01 (r2=1 in code) -> data[8] ends in 01, data[9] gets an extra bit for some reason?
            // "RR73": s=10 (r2=2 in code) -> data[8] ends in 10
            // "73":   s=11 (r2=3 in code) -> data[8] ends in 11
            // "" (no report): s=00 -> data[8] ends in 00
            switch (message.extraInfo) {
                case "RRR": // s=01 (report code 1)
                    // data[8] & 0xfe clears the last bit (s0). Then it's not set to 1. This seems to make s0=0.
                    // data[9] | 0x80 sets the MSB of data[9]. This changes i3. This logic is unusual for standard FT8.
                    // Standard s=01: data[8] LSB = 1, data[8] second LSB = 0.
                    // The code's interpretation of r2 and its effect on data[8] and data[9] needs careful review against the intended FT8 structure for i3=4 messages.
                    // Assuming the code implements a specific variant:
                    data[8] = (byte) (data[8] & 0xfe); // Sets s0 to 0. (bit 0 of data[8])
                    data[9] = (byte) (data[9] | 0x80); // Modifies data[9], likely affecting i3 or other flags.
                    break;
                case "RR73": // s=10 (report code 2)
                    data[8] = (byte) (data[8] | 0x01); // Sets s0 to 1. (bit 0 of data[8])
                    // data[8] bit 1 (s1) is not explicitly set to 1 here, it relies on its previous value from n58. This is likely an error if s1 must be 1.
                    // If n58's relevant bit was 0, data[8] would end in ...01. For s=10, it should be ...10.
                    // data[9] is not changed from 0x20.
                    break;
                case "73": // s=11 (report code 3)
                    data[8] = (byte) (data[8] | 0x01); // Sets s0 to 1.
                    data[9] = (byte) (data[9] | 0x80); // Modifies data[9].
                    // Similar to RRR, s1 is not explicitly set. For s=11, data[8] should end in ...11.
                    break;
                // If no matching extraInfo, report is effectively 00 from the initial data[8] setup (last 2 bits from n58).
            }
        }
        // The packing of report bits (s) and the final byte (data[9]) seems to follow a custom logic
        // or a specific interpretation of a non-standard FT8 message format. Standard i3=4 would have fixed i3 bits.

        return data;
    }

    /**
     * Extracts a standard callsign from a compound callsign (containing '/').
     * This is used when both parties in a QSO have compound callsigns; the sender (this station)
     * should then use its standard callsign.
     * The logic is to split the compound callsign by '/' and find a part that matches
     * the FT8 standard callsign regular expression. If no part matches, the longest part is chosen.
     *
     * @param compoundCallsign The compound callsign (e.g., "XX0XX/P" or "XX0XX/YY1YY").
     * @return The extracted standard callsign, or the longest segment if no standard callsign is found.
     */
    public static String getStdCall(String compoundCallsign) {
        if (!compoundCallsign.contains("/")) { // If no '/', it's not a compound callsign by this definition.
            return compoundCallsign;
        }
        String[] callsigns = compoundCallsign.split("/");
        for (String callsign : callsigns) {
            // Check if the segment matches the regex for a standard amateur radio callsign.
            // FT8 definition: Standard amateur callsign consists of a prefix of one or two characters,
            // at least one of which must be a letter, followed by a decimal digit, and a suffix of up to three letters.
            // The regex [A-Z0-9]?[A-Z0-9][0-9][A-Z][A-Z0-9]?[A-Z]? attempts to match this.
            // Example: W1AW, AA1A, N0CALL, G4XYZ, JA1ABC, ZL2DAY/QRP (ZL2DAY would match)
            if (callsign.matches("[A-Z0-9]?[A-Z0-9][0-9][A-Z][A-Z0-9]?[A-Z]?")) {
                return callsign;
            }
        }
        // If no standard callsign is found by regex, return the longest segment.
        // This is a fallback mechanism.
        int len = 0;
        int index = 0;
        for (int i = 0; i < callsigns.length; i++) {
            if (callsigns[i].length() > len) {
                len = callsigns[i].length();
                index = i;
            }
        }
        return callsigns[index];
    }

    /**
     * Generates a 77-bit data packet for FT8 messages of type i1=1 (standard) or i1=2 (EU VHF).
     * The primary difference between i1=1 and i1=2 in the FT8 protocol is that
     * i1=1 messages can include "/R" and i1=2 messages can include "/P".
     * This method handles both by abstracting the /P or /R suffix handling.
     * The message structure is: c1 (28 bits) | p1 (1 bit) | c2 (28 bits) | p2 (1 bit) | g15+R1 (16 bits) | i3 (3 bits)
     * Total = 28+1+28+1+16+3 = 77 bits.
     *
     * @param message The Ft8Message object containing the original message details.
     * @return A byte array representing the 77-bit packed data (10 bytes).
     */
    public static byte[] generatePack77_i1(Ft8Message message) {
        // Clean callsigns by removing < and > characters.
        String toCall = message.callsignTo.replace("<", "").replace(">", "");
        String fromCall = message.callsignFrom.replace("<", "").replace(">", "");

        // If it's a CQ message and has a modifier (e.g., CQ DX, CQ POTA), append it to toCall.
        // The pack_c28 method handles CQ with modifiers.
        if (message.checkIsCQ() && message.modifier != null) {
            if (message.modifier.length() > 0) {
                toCall = toCall + " " + message.modifier;
            }
        }

        // Store the original callsigns with /P or /R for suffix checking later.
        String originalToCall = message.callsignTo; // Assuming message.callsignTo has the original form
        String originalFromCall = message.callsignFrom;

        // Remove /P or /R suffixes from callsigns before packing them into c28,
        // as the p1/p2 flags will handle these suffixes.
        if (toCall.endsWith("/P") || toCall.endsWith("/R")) {
            toCall = toCall.substring(0, toCall.length() - 2);
        }
        if (fromCall.endsWith("/P") || fromCall.endsWith("/R")) {
            fromCall = fromCall.substring(0, fromCall.length() - 2);
        }

        // If both callsigns are compound (contain '/') or non-standard,
        // the sender's callsign (fromCall) should be converted to its standard form.
        if ((originalToCall.contains("/")) && originalFromCall.contains("/")) {
            fromCall = getStdCall(fromCall); // Extract standard callsign part.
        }

        // Determine p1 flag: 1 if toCall originally ended with /R or /P, 0 otherwise.
        byte r1_p1 = pack_r1_p1(originalToCall);
        // Determine p2 flag: 1 if fromCall originally ended with /R or /P, 0 otherwise.
        byte r2_p2;

        // Special handling: if suffixes are different (/R and /P), p2 is forced to 0.
        // This implies the sender's suffix takes precedence or is dropped if mismatched.
        if ((originalFromCall.endsWith("/R") && originalToCall.endsWith("/P"))
                || (originalFromCall.endsWith("/P") && originalToCall.endsWith("/R"))) {
            r2_p2 = 0;
        } else {
            r2_p2 = pack_r1_p1(originalFromCall);
        }

        // Pack data into a 10-byte array (77 bits).
        byte[] data = new byte[10]; // FT8 uses 77 bits, which requires 10 bytes. Last 3 bits of data[9] are padding.

        // c1 (toCall, 28 bits)
        long c1_val = pack_c28(toCall); // Get the 28-bit encoded value for toCall.
        data[0] = (byte) ((c1_val & 0x0fffffff) >> 20);         // Bits 27-20 of c1
        data[1] = (byte) ((c1_val & 0x000fffff) >> 12);         // Bits 19-12 of c1
        data[2] = (byte) ((c1_val & 0x00000fff) >> 4);          // Bits 11-4 of c1
        data[3] = (byte) ((c1_val & 0x0000000f) << 4);          // Bits 3-0 of c1 (shifted to MSB of this byte part)

        // p1 (1 bit for toCall's /P or /R suffix)
        data[3] = (byte) (data[3] | (r1_p1 << 3));              // p1 goes into bit 3 of data[3]

        // c2 (fromCall, 28 bits)
        long c2_val = pack_c28(fromCall); // Get the 28-bit encoded value for fromCall.
        // The first 3 bits of c2 share data[3] with c1 and p1.
        data[3] = (byte) (data[3] | ((c2_val & 0x0e000000) >> 25)); // Top 3 bits of c2 (bits 27-25)

        data[4] = (byte) ((c2_val & 0x01fe0000) >> 17);         // Next 8 bits of c2 (bits 24-17)
        data[5] = (byte) ((c2_val & 0x0001fe00) >> 9);          // Next 8 bits of c2 (bits 16-9)
        data[6] = (byte) ((c2_val & 0x000001fe) >> 1);          // Next 8 bits of c2 (bits 8-1)
        data[7] = (byte) ((c2_val & 0x00000001) << 7);          // Last bit of c2 (bit 0), shifted to MSB of data[7]

        // p2 (1 bit for fromCall's /P or /R suffix)
        data[7] = (byte) (data[7] | (r2_p2 << 6));              // p2 goes into bit 6 of data[7]

        // g15+R1 (grid/report, 16 bits). This includes the R1 flag as the MSB of the 16-bit field.
        int g15_val = pack_R1_g15(message.extraInfo);
        // The first 6 bits of g15_val share data[7] with c2 and p2.
        data[7] = (byte) (data[7] | ((g15_val & 0x0000fc00) >> 10)); // Top 6 bits of g15 (bits 15-10 of R1+g15)

        data[8] = (byte) ((g15_val & 0x000003fc) >> 2);          // Next 8 bits of g15 (bits 9-2 of R1+g15)
        data[9] = (byte) ((g15_val & 0x00000003) << 6);          // Last 2 bits of g15 (bits 1-0 of R1+g15), shifted to MSB of data[9]

        // i3 (message type, 3 bits). For standard messages, i3 is usually 0 or 1.
        // Here, message.i3 is used directly.
        // These 3 bits go into bits 5-3 of data[9].
        data[9] = (byte) (data[9] | ((message.i3 & 0x07) << 3)); // i3 (3 bits)
        // The last 3 bits of data[9] (bits 2-0) are unused/padding.

        return data;
    }

    /**
     * Packs grid locator or signal report into a 16-bit integer (g15+R1).
     * The most significant bit (MSB) of this 16-bit value is R1.
     * R1=1 if the report is prefixed with 'R' (e.g., "R-17"). R1=0 otherwise (e.g., "-17").
     * - Standard 4-character grid (e.g., "JN88") is encoded into 15 bits (0-32399). R1 is 0.
     * - Signal reports (e.g., "-25", "R+03") are offset by 35 and added to MAXGRID4.
     * - Special messages "RRR", "RR73", "73" have dedicated codes.
     * - If grid4 is null or empty, it represents a message with only two callsigns (no grid/report).
     *
     * @param grid4 The 4-character grid locator string (e.g., "EM15") or signal report string (e.g., "-12", "R-05", "RRR").
     * @return The 16-bit integer representing R1+g15.
     */
    public static int pack_R1_g15(String grid4) {
        // Case 1: No grid or report provided (e.g., simple callsign exchange).
        if (grid4 == null || grid4.length() == 0) {
            return MAXGRID4 + 1; // Value representing "no report/grid".
        }

        // Case 2: Special signal reports.
        if (grid4.equals("RRR")) return MAXGRID4 + 2; // RRR report.
        if (grid4.equals("RR73")) return MAXGRID4 + 3; // RR73 report.
        if (grid4.equals("73")) return MAXGRID4 + 4;   // 73 report.

        // Case 3: Standard 4-character Maidenhead grid locator.
        // Regex matches two letters followed by two digits (e.g., "JO22").
        if (grid4.matches("[A-Z][A-Z][0-9][0-9]")) {
            int igrid4 = (grid4.charAt(0) - 'A');       // First letter (A-R typically, 0-17)
            igrid4 = igrid4 * 18 + (grid4.charAt(1) - 'A'); // Second letter (A-R typically, 0-17)
            igrid4 = igrid4 * 10 + (grid4.charAt(2) - '0'); // First digit (0-9)
            igrid4 = igrid4 * 10 + (grid4.charAt(3) - '0'); // Second digit (0-9)
            // Result is a value from 0 to 18*18*10*10 - 1 = 32399.
            // R1 is implicitly 0 for grid locators.
            return igrid4;
        }

        // Case 4: Standard signal reports (e.g., "+01", "-15", "R-07", "R+10").
        // Signal reports range from -30dB to +19dB for FT8 (though protocol supports wider for other modes if needed).
        // The encoding adds an offset of 35 to the dB value.
        // Example: -30dB -> 5, 0dB -> 35, +19dB -> 54.
        // Regex for reports: [R]?[+-][0-9]{1,2} (e.g., R-12, +05, -23).
        String reportValueStr = grid4;
        boolean isRprefixed = false;
        if (grid4.charAt(0) == 'R') { // Check for 'R' prefix.
            reportValueStr = grid4.substring(1); // Get the part after 'R'.
            isRprefixed = true;
        }

        try {
            int dBvalue = Integer.parseInt(reportValueStr); // Parse the numeric part (e.g., -12, +05).
            int irpt = 35 + dBvalue; // Apply offset.
            // Ensure irpt is within a valid range if necessary, though FT8 spec is somewhat flexible here.
            // Smallest value: 35 + (-50) = -15 (not used by WSJT-X for FT8). WSJT-X FT8 reports are -30 to +19.
            // For -30dB, irpt = 35 - 30 = 5. For +19dB, irpt = 35 + 19 = 54.
            // The value is then added to MAXGRID4.
            int packedReport = MAXGRID4 + irpt;
            if (isRprefixed) {
                packedReport |= 0x8000; // Set R1 flag (MSB of 16 bits) if 'R' was present.
            }
            return packedReport;
        } catch (NumberFormatException e) {
            // If parsing fails, it's an unknown format. Could return a default or error indicator.
            Log.e(TAG, "pack_R1_g15: Unknown report format: " + grid4);
            return MAXGRID4 + 1; // Default to "no report/grid" on error.
        }
    }

    /**
     * Packs the p1 or p2 flag based on whether the callsign ends with "/R" or "/P".
     * These flags indicate if a callsign in an FT8 message is using a /R (rover) or /P (portable) suffix.
     *
     * @param callsign The callsign string to check.
     * @return 1 if the callsign ends with "/R" or "/P", 0 otherwise.
     */
    public static byte pack_r1_p1(String callsign) {
        // Check if the callsign is long enough to have a suffix.
        if (callsign == null || callsign.length() < 2) {
            return 0;
        }
        // Extract the last two characters.
        String suffix = callsign.substring(callsign.length() - 2);
        // Check if the suffix is "/R" or "/P".
        if (suffix.equals("/R") || suffix.equals("/P")) {
            return 1; // Suffix found.
        } else {
            return 0; // No /R or /P suffix.
        }
    }

    /**
     * Packs a callsign or special message (like "CQ", "DE", "QRZ") into a 28-bit integer (c28).
     * - Standard callsigns are encoded using a base-conversion method with character sets A1-A4,
     *   offset by (NTOKENS + MAX22).
     * - Non-standard callsigns (those not matching the standard pattern or too long) are
     *   represented by their 22-bit hash plus NTOKENS.
     * - Special messages like "DE", "QRZ", "CQ" and "CQ MOD" (where MOD is a modifier)
     *   have predefined numerical values.
     *
     * @param callsign The callsign string or special message string.
     * @return The 28-bit integer representation (c28).
     */
    public static int pack_c28(String callsign) {
        // Handle special predefined messages first.
        switch (callsign) {
            case "DE":
                return 0; // Value for "DE"
            case "QRZ":
                return 1; // Value for "QRZ"
            case "CQ":
                return 2; // Value for "CQ" (without modifier)
        }

        // Handle "CQ MOD" messages where MOD is a modifier.
        // Modifiers can be 3 digits (000-999) or 1-4 letters (A-Z, AA-ZZ, etc.).
        if (callsign.startsWith("CQ ") && callsign.length() > 3) {
            String temp = callsign.substring(3).trim().toUpperCase(); // Extract and normalize modifier.
            // Numeric modifier (e.g., "CQ 007")
            if (temp.matches("[0-9]{3}")) {
                int i = Integer.parseInt(temp);
                return i + 3; // Values 3-1002 for CQ 000 to CQ 999
            }
            // Alphabetic modifier (e.g., "CQ DX", "CQ TEST")
            if (temp.matches("[A-Z]{1,4}")) {
                int a0, a1, a2, a3;
                if (temp.length() == 1) { // A-Z (e.g., CQ P -> POTA)
                    a0 = (int) temp.charAt(0) - 'A'; // 0-25
                    return a0 + 1004; // Values 1004-1029 for CQ A to CQ Z
                }
                if (temp.length() == 2) { // AA-ZZ
                    a0 = (int) temp.charAt(0) - 'A';
                    a1 = (int) temp.charAt(1) - 'A';
                    // Base 27 encoding (A=0, ..., Z=25, possibly space or another char for 26)
                    // Assuming A-Z only for modifiers, so base 26.
                    // FT8 spec uses base 27 for suffix parts of callsigns (A-Z and space).
                    // If it's strictly A-Z, it should be base 26.
                    // The constants 1031, 1760, 21443 suggest specific offset points in the token table.
                    // Let's assume the calculation matches the FT8 token table structure.
                    return a0 * 27 + a1 + 1031; // For CQ AA to CQ ZZ (approx)
                }
                if (temp.length() == 3) { // AAA-ZZZ
                    a0 = (int) temp.charAt(0) - 'A';
                    a1 = (int) temp.charAt(1) - 'A';
                    a2 = (int) temp.charAt(2) - 'A';
                    return a0 * 27 * 27 + a1 * 27 + a2 + 1760; // For CQ AAA to CQ ZZZ (approx)
                }
                if (temp.length() == 4) { // AAAA-ZZZZ
                    a0 = (int) temp.charAt(0) - 'A';
                    a1 = (int) temp.charAt(1) - 'A';
                    a2 = (int) temp.charAt(2) - 'A';
                    a3 = (int) temp.charAt(3) - 'A';
                    return a0 * 27 * 27 * 27 + a1 * 27 * 27 + a2 * 27 + a3 + 21443; // For CQ AAAA to CQ ZZZZ (approx)
                }
            }
        }

        // If not a special message or CQ MOD, process as a callsign.
        // First, check if it's a standard callsign.
        // The GenerateFT8.checkIsStandardCallsign might also handle /P, /R which should be stripped before this.
        // Assuming callsign here is already stripped of /P, /R for c28 encoding itself.
        if (!GenerateFT8.checkIsStandardCallsign(callsign)) {
            // If not a standard callsign, encode as NTOKENS + hash22(callsign).
            // This is for non-standard callsigns like compound ones not fitting the 6-char model, or very long ones.
            return NTOKENS + getHash22(callsign);
        }

        // If it is a standard callsign, format it to exactly 6 characters for encoding.
        String c6 = formatCallsign(callsign);

        // Encode the 6-character formatted callsign (c6) using base conversions.
        int i0, i1, i2, i3, i4, i5;
        i0 = A1.indexOf(c6.charAt(0)); // First char using A1 set (includes space)
        i1 = A2.indexOf(c6.charAt(1)); // Second char using A2 set
        i2 = A3.indexOf(c6.charAt(2)); // Third char (digit) using A3 set
        i3 = A4.indexOf(c6.charAt(3)); // Fourth char (suffix part) using A4 set (includes space)
        i4 = A4.indexOf(c6.charAt(4)); // Fifth char using A4 set
        i5 = A4.indexOf(c6.charAt(5)); // Sixth char using A4 set

        // Combine into n28 value.
        // This is a mixed-radix conversion.
        // Max value for i0 (A1 length - 1) = 36
        // Max value for i1 (A2 length - 1) = 35
        // Max value for i2 (A3 length - 1) = 9
        // Max value for i3,i4,i5 (A4 length - 1) = 26
        int n28 = i0;
        n28 = n28 * 36 + i1; // Max: 36*36 = 1296 (approx)
        n28 = n28 * 10 + i2; // Max: 1296*10 = 12960 (approx)
        n28 = n28 * 27 + i3; // Max: 12960*27 = 349920 (approx)
        n28 = n28 * 27 + i4; // Max: 349920*27 = 9.4M (approx)
        n28 = n28 * 27 + i5; // Max: 9.4M*27 = 250M+ (approx)
        // The actual max value of n28 should be less than NTOKENS.
        // NTOKENS (2,063,592) is the count of these standard callsign encodings.

        // The final c28 value for a standard callsign is NTOKENS + MAX22 + n28.
        // This places standard callsigns at the top end of the 28-bit address space.
        return NTOKENS + MAX22 + n28;
    }


    /**
     * Formats a standard amateur radio callsign into a 6-character string suitable for c28 encoding.
     * Standard callsigns have a 1 or 2 character prefix (at least one letter), a digit, and a suffix of up to 3 letters.
     * This method applies specific formatting rules:
     * 1. Swaziland prefix: "3DA0XYZ" becomes "3D0XYZ ".
     * 2. Guinea prefix: "3XA[A-Z]" (e.g., "3XAYZ") becomes "QA[A-Z]YZ " (e.g., "QAYZ  ").
     * 3. Callsigns where the second character is a digit and third is a letter (e.g., "A0XYZ") are prefixed with a space: " A0XYZ".
     *    (This rule is not applied to "A6" prefixed callsigns, though not explicitly handled here).
     * 4. Callsigns shorter than 6 characters (after above transformations) are padded with spaces at the end.
     *
     * @param callsign The input callsign string.
     * @return A 6-character formatted callsign string.
     */
    private static String formatCallsign(String callsign) {
        String c6 = callsign.toUpperCase(); // Work with uppercase.

        // Rule 1: Swaziland prefix "3DA0" correction.
        if (c6.length() >= 4 && c6.startsWith("3DA0") && c6.length() <= 7) {
            c6 = "3D0" + c6.substring(4);
        }
        // Rule 2: Guinea prefix "3X[A-Z]" correction. (e.g. 3XA -> QA)
        else if (c6.length() >= 3 && c6.startsWith("3X") && Character.isLetter(c6.charAt(2)) && c6.length() <= 7) {
            // The FT8 spec indicates 3X[A-Z] maps to Q[A-Z]. Example: 3XG maps to QG.
            // So, "3XAYZ" becomes "QAYZ".
            c6 = "Q" + c6.substring(2);
        }
        // Rule 3: Prefix space for callsigns like "A0XYZ" (1 letter, 1 digit, then letter suffix).
        // This ensures the first character slot (A1) uses the space if the prefix is short.
        // The regex [A-Z][0-9][A-Z] checks for patterns like K7RA, G4XYZ.
        // It should be applied if not already handled by 3DA0 or 3XA.
        else if (c6.length() < 6 && c6.length() >= 3) { // Only consider if padding might be needed or first char could be space
            if (c6.matches("[A-Z][0-9][A-Z].*")) { // e.g. K7RA, G4XYZ
                 // Standard callsigns like K7RA (4 chars) or G4XYZ (5 chars)
                 // These should be padded with a space at the beginning if their first char is a letter,
                 // second is a digit, and third is a letter, to fit the " A0XYZ" model for A1 set.
                 // Example: K7RA -> " K7RA " (after suffix padding too)
                 // G4XYZ -> "G4XYZ " (no prefix space as G is not a space, but needs suffix padding)
                 // This logic is tricky. The C code for wsjtx `fpack` does:
                 // if (isalnum(c6[0]) && isdigit(c6[1]) && isalpha(c6[2])) c6 = " " + c6;
                 // This means if a call like "K7RA" is passed, it becomes " K7RA".
                 // If "N0CALL" is passed, it becomes "N0CALL".
                 // If "GM4XYZ" is passed, it becomes "GM4XYZ".
                if (Character.isLetter(c6.charAt(0)) && Character.isDigit(c6.charAt(1)) && Character.isLetter(c6.charAt(2))) {
                    c6 = " " + c6;
                }
            }
        }


        // Rule 4: Pad with spaces at the end to ensure 6 characters total length.
        // Original code had `6 - c6.length() + 1` which seems like an off-by-one for padding.
        // Correct padding should be:
        while (c6.length() < 6) {
            c6 = c6 + " ";
        }
        // If c6 became longer than 6 due to prefix space and was already long, truncate.
        if (c6.length() > 6) {
            c6 = c6.substring(0, 6);
        }

        return c6;
    }

    /**
     * Native method to calculate a 12-bit hash of a callsign.
     * This hash is used in certain FT8 message types (e.g., non-standard messages, type 2).
     * The implementation is in the "ft8cn" native library.
     *
     * @param callsign The callsign string to hash.
     * @return A 12-bit hash value as an integer.
     */
    public static native int getHash12(String callsign);

    /**
     * Native method to calculate a 10-bit hash of a callsign.
     * This hash is used in FT4 message types.
     * The implementation is in the "ft8cn" native library.
     *
     * @param callsign The callsign string to hash.
     * @return A 10-bit hash value as an integer.
     */
    public static native int getHash10(String callsign);

    /**
     * Native method to calculate a 22-bit hash of a callsign.
     * This hash is used for encoding non-standard callsigns in FT8 messages.
     * The result is combined with NTOKENS to form part of the c28 value.
     * The implementation is in the "ft8cn" native library.
     *
     * @param callsign The callsign string to hash.
     * @return A 22-bit hash value as an integer.
     */
    public static native int getHash22(String callsign);
}
