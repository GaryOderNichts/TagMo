package com.hiddenramblings.tagmo.nfc;

import com.hiddenramblings.tagmo.R;
import com.hiddenramblings.tagmo.TagMo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class TagWriter {

    private static final String TAG = TagWriter.class.getSimpleName();

    public static void writeToTagRaw(NTAG215 mifare, byte[] tagData, boolean validateNtag) throws Exception {
        validate(mifare, tagData, validateNtag);
        validateBlankTag(mifare);

        try {
            byte[][] pages = TagUtil.splitPages(tagData);
            writePages(mifare, 3, 129, pages);
            TagMo.Debug(TAG, R.string.data_write);
        } catch (Exception e) {
            throw new Exception(TagMo.getStringRes(R.string.data_write_error), e);
        }
        try {
            writePassword(mifare);
            TagMo.Debug(TAG, R.string.password_write);
        } catch (Exception e) {
            throw new Exception(TagMo.getStringRes(R.string.password_write_error), e);
        }
        try {
            writeLockInfo(mifare);
            TagMo.Debug(TAG, R.string.lock_write);
        } catch (Exception e) {
            throw new Exception(TagMo.getStringRes(R.string.lock_write_error), e);
        }
    }

    private static void validateBlankTag(NTAG215 mifare) throws Exception {
        byte[] lockPage = mifare.readPages(0x02);
        TagMo.Debug(TAG, Util.bytesToHex(lockPage));
        if (lockPage[2] == (byte) 0x0F && lockPage[3] == (byte) 0xE0) {
            TagMo.Debug(TAG, R.string.locked);
            throw new Exception(TagMo.getStringRes(R.string.tag_already_written));
        }
        TagMo.Debug(TAG, R.string.unlocked);
    }

    public static void writeToTagAuto(
            NTAG215 mifare, byte[] tagData, KeyManager keyManager,
            boolean validateNtag, boolean supportPowerTag) throws Exception {
        byte[] idPages = mifare.readPages(0);
        if (idPages == null || idPages.length != NfcByte.PAGE_SIZE * 4)
            throw new Exception(TagMo.getStringRes(R.string.fail_read_size));

        boolean isPowerTag = false;
        if (supportPowerTag) {
            byte[] sig = mifare.transceive(NfcByte.SIG_CMD);
            isPowerTag = compareRange(sig, NfcByte.POWERTAG_SIGNATURE, 0, NfcByte.POWERTAG_SIGNATURE.length);
        }

        TagMo.Debug(TAG, R.string.power_tag_exists, String.valueOf(isPowerTag));

        tagData = TagUtil.decrypt(keyManager, tagData);
        if (isPowerTag) {
            //use a pre-determined static id for powertag
            tagData = TagUtil.patchUid(NfcByte.POWERTAG_IDPAGES, tagData);
        } else {
            tagData = TagUtil.patchUid(idPages, tagData);
        }
        tagData = TagUtil.encrypt(keyManager, tagData);

        TagMo.Debug(TAG, Util.bytesToHex(tagData));

        if (!isPowerTag) {
            validate(mifare, tagData, validateNtag);
            validateBlankTag(mifare);
        }

        if (isPowerTag) {
            byte[] oldid = mifare.getTag().getId();
            if (oldid == null || oldid.length != 7)
                throw new Exception(TagMo.getStringRes(R.string.fail_read_uid));

            TagMo.Debug(TAG, R.string.old_uid, Util.bytesToHex(oldid));

            byte[] page10 = mifare.readPages(0x10);
            TagMo.Debug(TAG, R.string.page_ten, Util.bytesToHex(page10));

            String page10bytes = Util.bytesToHex(new byte[]{page10[0], page10[3]});

            byte[] ptagKeySuffix = PTagKeyManager.getKey(oldid, page10bytes);
            byte[] ptagKey = Util.hexStringToByteArray(NfcByte.POWERTAG_KEY);
            System.arraycopy(ptagKeySuffix, 0, ptagKey, 8, 8);

            TagMo.Debug(TAG, R.string.ptag_key, Util.bytesToHex(ptagKey));

            mifare.transceive(NfcByte.COMP_WRITE_CMD);
            mifare.transceive(ptagKey);

            if (!(idPages[0] == (byte) 0xFF && idPages[1] == (byte) 0xFF))
                doAuth(mifare);
        }

        byte[][] pages = TagUtil.splitPages(tagData);
        if (isPowerTag) {
            byte[] zeropage = Util.hexStringToByteArray("00000000");
            mifare.writePage(0x86, zeropage); //PACK
            writePages(mifare, 0x01, 0x84, pages);
            mifare.writePage(0x85, zeropage); //PWD
            mifare.writePage(0x00, pages[0]); //UID
            mifare.writePage(0x00, pages[0]); //UID
        } else {
            try {
                writePages(mifare, 3, 129, pages);
                TagMo.Debug(TAG, R.string.data_write);
            } catch (Exception e) {
                throw new Exception(TagMo.getStringRes(R.string.data_write_error), e);
            }
            try {
                writePassword(mifare);
                TagMo.Debug(TAG, R.string.password_write);
            } catch (Exception e) {
                throw new Exception(TagMo.getStringRes(R.string.password_write_error), e);
            }
            try {
                writeLockInfo(mifare);
                TagMo.Debug(TAG, R.string.lock_write);
            } catch (Exception e) {
                throw new Exception(TagMo.getStringRes(R.string.lock_write_error), e);
            }
        }
    }

    public static byte[] writeAmiiqoAuto(NTAG215 tag, byte[] tagData, int active_bank) throws Exception {
        if (doAmiiqoAuth(tag, tag.fastRead(0, 0))) {
            if (tag.amiiboFastWrite(0, active_bank, tagData)) {
                byte[] result = new byte[8];
                System.arraycopy(tagData, 84, result, 0, result.length);
                return result;
            } else {
                throw new Exception(TagMo.getStringRes(R.string.amiiqo_write_error));
            }
        } else {
            throw new Exception(TagMo.getStringRes(R.string.amiiqo_auth_error));
        }
    }

    public static void restoreTag(NTAG215 mifare, byte[] tagData, boolean ignoreUid, KeyManager keyManager, boolean validateNtag) throws Exception {
        if (!ignoreUid)
            validate(mifare, tagData, validateNtag);
        else {
            byte[] liveData = readFromTag(mifare);
            if (!compareRange(liveData, tagData, 0, 9)) {
                //restoring to different tag: transplant mii and appdata to livedata and re-encrypt

                liveData = TagUtil.decrypt(keyManager, liveData);
                tagData = TagUtil.decrypt(keyManager, tagData);

                System.arraycopy(tagData, 0x08, liveData, 0x08, 0x1B4 - 0x08);

                tagData = TagUtil.encrypt(keyManager, liveData);
            }
        }

        doAuth(mifare);
        byte[][] pages = TagUtil.splitPages(tagData);
        writePages(mifare, 4, 12, pages);
        writePages(mifare, 32, 129, pages);
    }

    static void validate(NTAG215 mifare, byte[] tagData, boolean validateNtag) throws Exception {
        if (tagData == null)
            throw new Exception(TagMo.getStringRes(R.string.no_source_data));

        if (validateNtag) {
            try {
                byte[] versionInfo = mifare.transceive(new byte[]{(byte) 0x60});
                if (versionInfo == null || versionInfo.length != 8)
                    throw new Exception(TagMo.getStringRes(R.string.tag_version_error));
                if (versionInfo[0x02] != (byte) 0x04 || versionInfo[0x06] != (byte) 0x11)
                    throw new Exception(TagMo.getStringRes(R.string.tag_format_error));
            } catch (Exception e) {
                TagMo.Error(TAG, R.string.version_error, e);
                throw e;
            }
        }

        byte[] pages = mifare.readPages(0);
        if (pages == null || pages.length != NfcByte.PAGE_SIZE * 4)
            throw new Exception(TagMo.getStringRes(R.string.fail_read_size));

        if (!compareRange(pages, tagData, 0, 9))
            throw new Exception(TagMo.getStringRes(R.string.fail_mismatch_uid));

        TagMo.Error(TAG, R.string.validation_success);
    }

    static boolean compareRange(byte[] data, byte[] data2, int data2offset, int len) {
        for (int i = data2offset, j = 0; j < len; i++, j++) {
            if (data[j] != data2[i])
                return false;
        }
        return true;
    }

    public static byte[] readFromTag(NTAG215 tag) throws Exception {
        byte[] tagData = new byte[NfcByte.TAG_FILE_SIZE];
        int pageCount = NfcByte.TAG_FILE_SIZE / NfcByte.PAGE_SIZE;

        for (int i = 0; i < pageCount; i += NfcByte.BULK_READ_PAGE_COUNT) {
            byte[] pages = tag.readPages(i);
            if (pages == null || pages.length != NfcByte.PAGE_SIZE * NfcByte.BULK_READ_PAGE_COUNT)
                throw new Exception(TagMo.getStringRes(R.string.fail_invalid_size));

            int dstIndex = i * NfcByte.PAGE_SIZE;
            int dstCount = Math.min(NfcByte.BULK_READ_PAGE_COUNT * NfcByte.PAGE_SIZE, tagData.length - dstIndex);

            System.arraycopy(pages, 0, tagData, dstIndex, dstCount);
        }

        TagMo.Debug(TAG, Util.bytesToHex(tagData));
        return tagData;
    }

    static void writePages(NTAG215 tag, int pagestart, int pageend, byte[][] data) throws IOException {
        for (int i = pagestart; i <= pageend; i++) {
            tag.writePage(i, data[i]);
            TagMo.Debug(TAG, R.string.write_page, String.valueOf(i));
        }
    }

    static void writePassword(NTAG215 tag) throws IOException {
        byte[] pages0_1 = tag.readPages(0);

        if (pages0_1 == null || pages0_1.length != NfcByte.PAGE_SIZE * 4)
            throw new IOException(TagMo.getStringRes(R.string.read_failed));

        byte[] uid = TagUtil.uidFromPages(pages0_1);
        byte[] password = TagUtil.keygen(uid);

        TagMo.Debug(TAG, R.string.password, Util.bytesToHex(password));

        TagMo.Debug(TAG, R.string.write_pack);
        tag.writePage(0x86, new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0, (byte) 0});

        TagMo.Debug(TAG, R.string.write_pwd);
        tag.writePage(0x85, password);
    }

    static void writeLockInfo(NTAG215 tag) throws IOException {
        byte[] pages = tag.readPages(0);

        if (pages == null || pages.length != NfcByte.PAGE_SIZE * 4)
            throw new IOException(TagMo.getStringRes(R.string.read_failed));

        tag.writePage(2, new byte[]{pages[2 * NfcByte.PAGE_SIZE],
                pages[(2 * NfcByte.PAGE_SIZE) + 1], (byte) 0x0F, (byte) 0xE0}); // lock bits
        tag.writePage(130, new byte[]{(byte) 0x01, (byte) 0x00, (byte) 0x0F, (byte) 0x00});
        // dynamic lock bits. should the last bit be 0xBD according to the nfc docs though:
        // Remark: Set all bits marked with RFUI to 0, when writing to the dynamic lock bytes.
        tag.writePage(131, new byte[]{(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x04}); // config
        tag.writePage(132, new byte[]{(byte) 0x5F, (byte) 0x00, (byte) 0x00, (byte) 0x00}); // config
    }

    static void doAuth(NTAG215 tag) throws Exception {
        byte[] pages0_1 = tag.readPages(0);

        if (pages0_1 == null || pages0_1.length != NfcByte.PAGE_SIZE * 4)
            throw new Exception(TagMo.getStringRes(R.string.read_failed));

        byte[] uid = TagUtil.uidFromPages(pages0_1);
        byte[] password = TagUtil.keygen(uid);

        TagMo.Debug(TAG, R.string.password, Util.bytesToHex(password));

        byte[] auth = new byte[]{
                (byte) 0x1B,
                password[0],
                password[1],
                password[2],
                password[3]
        };
        byte[] response = tag.transceive(auth);
        if (response == null)
            throw new Exception(TagMo.getStringRes(R.string.auth_null));
        String respStr = Util.bytesToHex(response);
        TagMo.Error(TAG, R.string.auth_response, respStr);
        if (!"8080".equals(respStr)) {
            throw new Exception(TagMo.getStringRes(R.string.auth_failed));
        }
    }

    private static boolean doAmiiqoAuth(NTAG215 tag, byte[] password) throws Exception {
        if (password == null || password.length != 4) {
            return false;
        }
        byte[] req = new byte[5];
        req[0] = NfcByte.CMD_PWD_AUTH;
        try {
            System.arraycopy(password, 0, req, 1, 4);
            password = tag.transceive(req);
        } catch (Exception e) {
            return false;
        }
        if (password == null || password.length != 2) {
            return false;
        }
        return password[0] == Byte.MIN_VALUE && password[1] == Byte.MIN_VALUE;
    }

    public static ArrayList<String> readFromTags(NTAG215 tag, int numBanks) throws Exception {
        ArrayList<String> tags = new ArrayList<>();
        int i = 0;
        while (i < (numBanks & 0xFF)) {
            try {
                byte[] tagData = tag.amiiboFastRead(0x15, 0x16, i);
                if (tagData == null || tagData.length != 8) {
                    throw new Exception();
                }
                tags.add(Util.bytesToHex(tagData));
                i++;
            } catch (Exception e) {
                TagMo.Debug(TAG, TagMo.getStringRes(R.string.fail_amiiqo_bank_parse));
            }
        }
        return tags;
    }

    public static byte[] amiiqoDeleteTag(NTAG215 tag, int active_bank)  throws Exception {
        if (doAmiiqoAuth(tag, tag.fastRead(0, 0))) {
            byte[] tagData = Util.hexStringToByteArray(new String(
                    new char[1080]).replace("\u0000", "F"));
            if (tag.amiiboFastWrite(0, active_bank, tagData)) {
                byte[] result = new byte[8];
                System.arraycopy(tagData, 84, result, 0, result.length);
                return result;
            } else {
                throw new Exception(TagMo.getStringRes(R.string.amiiqo_write_error));
            }
        } else {
            throw new Exception(TagMo.getStringRes(R.string.amiiqo_write_error));
        }
    }

    public static int getIndexFromDisplay(int value) {
        return value - 1;
    }

    public static int getDisplayFromIndex(int value) {
        return value + 1;
    }

    public static byte[] getAmiiqoBankDetails(NTAG215 tag) {
        return tag.amiiboGetVersion();
    }

    public static boolean needsFirmwareUpdate(NTAG215 tag) {
        byte[] version = getAmiiqoBankDetails(tag);
        return !((version.length != 4 || version[3] == (byte) 0x03)
                && !(version.length == 2 && version[0] == 100 && version[1] == 0));
    }

    public static int getAmiiqoBankCount(NTAG215 tag) {
        return getAmiiqoBankDetails(tag)[1] & 0xFF;
    }

    public static int getAmiiqoActiveBank(NTAG215 tag) {
        return getDisplayFromIndex(getAmiiqoBankDetails(tag)[0] & 0xFF);
    }

    public static String getAmiiqoSignature(NTAG215 tag) {
        byte[] signature = tag.readAmiiqoSignature();
        if (signature != null)
            return Util.bytesToHex(tag.readAmiiqoSignature()).substring(0, 22);
        return null;
    }

    public static boolean flashFirmware(NTAG215 tag) throws Exception {
        byte[] response = new byte[1];
        int records_a = 0;
        int records_r = 0;
        response[0] = (byte) -1;
        tag.initFirmware();
        tag.getVersion();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    TagMo.getContext().getResources().openRawResource(R.raw.firmware)));
            while (true) {
                String strLine = br.readLine();
                if (strLine == null) {
                    break;
                }
                String[] parts = strLine.replaceAll("\\s+", " ").split(" ");
                int i;
                if (parts.length < 1) {
                    break;
                } else if (parts[0].equals("C-APDU")) {
                    byte[] apdu_buf = new byte[(parts.length - 1)];
                    for (i = 1; i < parts.length; i++) {
                        apdu_buf[i - 1] = Util.hex2byte(parts[i]);
                    }
                    int sz = apdu_buf[4] & 0xFF;
                    byte[] iso_cmd = new byte[sz];
                    if (apdu_buf[4] + 5 <= apdu_buf.length && apdu_buf[4] <= iso_cmd.length) {
                        for (i = 0; i < sz; i++) {
                            iso_cmd[i] = apdu_buf[i + 5];
                        }
                        boolean done = false;
                        for (i = 0; i < 10; i++) {
                            response = tag.transceive(iso_cmd);
                            if (response != null) {
                                done = true;
                                break;
                            }
                        }
                        if (done) {
                            records_a++;
                        } else {
                            throw new Exception(TagMo.getStringRes(R.string.firmware_failed, 1));
                        }
                    }
                    return false;
                } else if (parts[0].equals("C-RPDU")) {
                    byte[] rpdu_buf = new byte[(parts.length - 1)];
                    if (response.length != parts.length - 3) {
                        throw new Exception(TagMo.getStringRes(R.string.firmware_failed, 2));
                    }
                    for (i = 1; i < parts.length; i++) {
                        rpdu_buf[i - 1] = Util.hex2byte(parts[i]);
                    }
                    for (i = 0; i < rpdu_buf.length - 2; i++) {
                        if (rpdu_buf[i] != response[i]) {
                            throw new Exception(TagMo.getStringRes(R.string.firmware_failed, 3));
                        }
                    }
                    records_r++;
                } else if (!parts[0].equals("RESET") && parts[0].equals("LOGIN")) {

                }
            }
            br.close();
            return true;
        } catch (IOException e) {
            throw new Exception(TagMo.getStringRes(R.string.firmware_failed, 4));
        }
    }
}
