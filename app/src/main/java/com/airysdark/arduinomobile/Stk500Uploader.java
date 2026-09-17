package com.airysdark.arduinomobile;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class Stk500Uploader {
    interface Progress {
        void onProgress(String message, int percent);
    }

    private static final int STK_OK = 0x10;
    private static final int STK_INSYNC = 0x14;
    private static final int CRC_EOP = 0x20;
    private static final int PAGE_SIZE = 128;

    static void flash(UsbSerialPort port, String hexText, int baud, Progress progress) throws Exception {
        IntelHex image = IntelHex.parse(hexText);
        progress.onProgress("Opening bootloader at " + baud + " baud...", 0);
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

        try { port.setDTR(true); } catch (Exception ignored) {}
        try { port.setRTS(true); } catch (Exception ignored) {}
        Thread.sleep(80);
        try { port.setDTR(false); } catch (Exception ignored) {}
        try { port.setRTS(false); } catch (Exception ignored) {}
        Thread.sleep(350);

        boolean synced = false;
        for (int i = 0; i < 12; i++) {
            drain(port);
            port.write(new byte[]{0x30, CRC_EOP}, 1000);
            try {
                expectOk(port);
                synced = true;
                break;
            } catch (Exception ignored) {
                Thread.sleep(150);
            }
        }
        if (!synced) throw new IllegalStateException("Could not sync with the Arduino bootloader.");

        command(port, new byte[]{0x50, CRC_EOP}); // enter programming mode
        try { command(port, new byte[]{0x52, CRC_EOP}); } catch (Exception ignored) {} // chip erase where supported

        int pages = (image.length + PAGE_SIZE - 1) / PAGE_SIZE;
        int writtenPages = 0;
        for (int page = 0; page < pages; page++) {
            int start = page * PAGE_SIZE;
            int length = Math.min(PAGE_SIZE, image.length - start);
            byte[] pageData = Arrays.copyOfRange(image.data, start, start + length);
            if (allFF(pageData)) continue;

            loadAddress(port, start / 2);
            byte[] packet = new byte[5 + pageData.length];
            packet[0] = 0x64;
            packet[1] = (byte) ((pageData.length >> 8) & 0xFF);
            packet[2] = (byte) (pageData.length & 0xFF);
            packet[3] = 'F';
            System.arraycopy(pageData, 0, packet, 4, pageData.length);
            packet[packet.length - 1] = CRC_EOP;
            command(port, packet);

            writtenPages++;
            int percent = Math.min(80, (int) (((page + 1) * 80.0) / Math.max(1, pages)));
            progress.onProgress("Writing flash page " + (page + 1) + "/" + pages, percent);
        }

        if (writtenPages == 0) throw new IllegalStateException("HEX file contains no programmable data.");

        progress.onProgress("Verifying flash...", 82);
        for (int page = 0; page < pages; page++) {
            int start = page * PAGE_SIZE;
            int length = Math.min(PAGE_SIZE, image.length - start);
            byte[] expected = Arrays.copyOfRange(image.data, start, start + length);
            if (allFF(expected)) continue;

            loadAddress(port, start / 2);
            byte[] request = new byte[]{0x74, (byte) ((length >> 8) & 0xFF), (byte) (length & 0xFF), 'F', CRC_EOP};
            port.write(request, 1000);
            int first = readByte(port, 1500);
            if (first != STK_INSYNC) throw new IllegalStateException("Bootloader verification did not enter sync.");
            byte[] actual = readExact(port, length, 2000);
            int last = readByte(port, 1500);
            if (last != STK_OK) throw new IllegalStateException("Bootloader verification failed.");
            if (!Arrays.equals(expected, actual)) {
                throw new IllegalStateException("Flash verification mismatch at address 0x" + Integer.toHexString(start));
            }

            int percent = 82 + Math.min(17, (int) (((page + 1) * 17.0) / Math.max(1, pages)));
            progress.onProgress("Verifying page " + (page + 1) + "/" + pages, percent);
        }

        try { command(port, new byte[]{0x51, CRC_EOP}); } catch (Exception ignored) {}
        progress.onProgress("Flash complete and verified.", 100);
    }

    private static void loadAddress(UsbSerialPort port, int wordAddress) throws Exception {
        command(port, new byte[]{0x55, (byte) (wordAddress & 0xFF), (byte) ((wordAddress >> 8) & 0xFF), CRC_EOP});
    }

    private static void command(UsbSerialPort port, byte[] command) throws Exception {
        port.write(command, 1500);
        expectOk(port);
    }

    private static void expectOk(UsbSerialPort port) throws Exception {
        int first = readByte(port, 1500);
        int second = readByte(port, 1500);
        if (first != STK_INSYNC || second != STK_OK) {
            throw new IllegalStateException(String.format("Unexpected bootloader response: %02X %02X", first, second));
        }
    }

    private static int readByte(UsbSerialPort port, int timeoutMs) throws Exception {
        byte[] one = readExact(port, 1, timeoutMs);
        return one[0] & 0xFF;
    }

    private static byte[] readExact(UsbSerialPort port, int length, int timeoutMs) throws Exception {
        byte[] result = new byte[length];
        int offset = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (offset < length && System.currentTimeMillis() < deadline) {
            byte[] temp = new byte[Math.min(256, length - offset)];
            int count = port.read(temp, Math.min(250, timeoutMs));
            if (count > 0) {
                System.arraycopy(temp, 0, result, offset, count);
                offset += count;
            }
        }
        if (offset != length) throw new IllegalStateException("Timed out waiting for bootloader data.");
        return result;
    }

    private static void drain(UsbSerialPort port) {
        try {
            byte[] buffer = new byte[256];
            while (port.read(buffer, 20) > 0) { }
        } catch (Exception ignored) { }
    }

    private static boolean allFF(byte[] data) {
        for (byte value : data) if ((value & 0xFF) != 0xFF) return false;
        return true;
    }

    private static final class IntelHex {
        final byte[] data;
        final int length;

        IntelHex(byte[] data, int length) {
            this.data = data;
            this.length = length;
        }

        static IntelHex parse(String text) {
            byte[] memory = new byte[32768];
            Arrays.fill(memory, (byte) 0xFF);
            int upper = 0;
            int highest = 0;

            String[] lines = text.replace("\r", "").split("\n");
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (!line.startsWith(":")) throw new IllegalArgumentException("Invalid Intel HEX line: " + line);

                int count = hexByte(line, 1);
                int address = (hexByte(line, 3) << 8) | hexByte(line, 5);
                int type = hexByte(line, 7);
                int checksum = count + (address >> 8) + (address & 0xFF) + type;
                byte[] payload = new byte[count];
                for (int i = 0; i < count; i++) {
                    payload[i] = (byte) hexByte(line, 9 + i * 2);
                    checksum += payload[i] & 0xFF;
                }
                checksum += hexByte(line, 9 + count * 2);
                if ((checksum & 0xFF) != 0) throw new IllegalArgumentException("Intel HEX checksum error.");

                if (type == 0x00) {
                    int absolute = upper + address;
                    int end = absolute + count;
                    if (absolute < 0 || end > memory.length) {
                        throw new IllegalArgumentException("Firmware exceeds ATmega328P 32 KB flash range.");
                    }
                    System.arraycopy(payload, 0, memory, absolute, count);
                    highest = Math.max(highest, end);
                } else if (type == 0x01) {
                    break;
                } else if (type == 0x04 && count == 2) {
                    upper = (((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)) << 16;
                } else if (type == 0x02 && count == 2) {
                    upper = (((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)) << 4;
                }
            }
            return new IntelHex(Arrays.copyOf(memory, Math.max(1, highest)), highest);
        }

        private static int hexByte(String line, int index) {
            return Integer.parseInt(line.substring(index, index + 2), 16);
        }
    }

    private Stk500Uploader() {}
}
