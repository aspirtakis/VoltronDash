/*
 * Votol Settings Dump & Restore
 *
 * Wiring (ESP32-S3):
 *   ESP32 GPIO9  (RX) <- Votol TX
 *   ESP32 GPIO10 (TX) -> Votol RX
 *   ESP32 GND         -  Votol GND
 *
 * Usage:
 *   1. Open Serial Monitor at 115200 baud
 *   2. Type 'd' + Enter -> dumps all settings as hex
 *   3. Copy the SETTINGS_HEX line from output
 *   4. Connect new controller
 *   5. Type 'r' + Enter -> paste hex when prompted, then Enter to restore
 *   6. Type 't' + Enter -> test connection with SHOW command
 *   7. Type 'p' + Enter -> probe all command codes (discovery)
 */

#define VOTOL_RX_PIN 9
#define VOTOL_TX_PIN 10
#define VOTOL_BAUD   9600

// --- Known Votol commands ---
// Header is always C9 14, then command type byte, then command code

// SHOW command (telemetry) - with calibration values
static const uint8_t SHOW_CMD[] = {
  0xC9, 0x14, 0x02, 0x53, 0x48, 0x4F, 0x57, 0x00,
  0x00, 0x00, 0x00, 0x00, 0xAA, 0x00, 0x00, 0x00,
  0x1E, 0xAA, 0x04, 0x67, 0x00, 0xF3, 0x52, 0x0D
};

// Config read attempt - various known patterns from Endless Sphere
// Byte 3 = 0x52 'R' = Read?, 0x50 'P' = Program/Parameters
// We'll try multiple command patterns

uint8_t rxBuf[256];  // large buffer for any response
uint8_t configDump[512]; // stored config data
int configLen = 0;

uint8_t xorChecksum(const uint8_t* data, int len) {
  uint8_t c = 0;
  for (int i = 0; i < len; i++) c ^= data[i];
  return c;
}

// Build a 24-byte Votol command packet
void buildCmd(uint8_t* pkt, uint8_t cmdByte, const uint8_t* payload, int payloadLen) {
  memset(pkt, 0, 24);
  pkt[0] = 0xC9;
  pkt[1] = 0x14;
  pkt[2] = 0x02;
  pkt[3] = cmdByte;
  if (payload && payloadLen > 0) {
    int copyLen = payloadLen < 18 ? payloadLen : 18;
    memcpy(&pkt[4], payload, copyLen);
  }
  pkt[22] = xorChecksum(pkt, 22);
  pkt[23] = 0x0D;
}

// Send command and read response (skipping echo)
int sendAndRead(const uint8_t* cmd, int cmdLen, uint8_t* resp, int respMax, int timeoutMs) {
  // Flush RX
  while (Serial2.available()) Serial2.read();

  // Send
  Serial2.write(cmd, cmdLen);
  Serial2.flush();

  // Read all bytes (echo + response)
  int total = 0;
  uint8_t allBuf[128];
  unsigned long start = millis();
  unsigned long lastByte = millis();

  while (millis() - start < timeoutMs) {
    if (Serial2.available()) {
      if (total < 128) {
        allBuf[total++] = Serial2.read();
      } else {
        Serial2.read();
      }
      lastByte = millis();
    } else if (total > 0 && millis() - lastByte > 50) {
      break; // gap after receiving data = done
    }
  }

  // Skip echo (first cmdLen bytes that match what we sent)
  int respStart = 0;
  if (total > cmdLen) {
    // Check if first bytes are echo
    bool isEcho = true;
    for (int i = 0; i < cmdLen && i < total; i++) {
      if (allBuf[i] != cmd[i]) { isEcho = false; break; }
    }
    if (isEcho) {
      respStart = cmdLen;
    }
  }

  int respLen = total - respStart;
  if (respLen > respMax) respLen = respMax;
  if (respLen > 0) {
    memcpy(resp, &allBuf[respStart], respLen);
  }
  return respLen;
}

void printHex(const uint8_t* data, int len) {
  for (int i = 0; i < len; i++) {
    if (data[i] < 0x10) Serial.print("0");
    Serial.print(data[i], HEX);
    if (i < len - 1) Serial.print(" ");
  }
}

// Test connection with SHOW
void testConnection() {
  Serial.println("\n=== TEST CONNECTION (SHOW) ===");
  uint8_t resp[48];
  int len = sendAndRead(SHOW_CMD, sizeof(SHOW_CMD), resp, 48, 500);

  if (len >= 24 && resp[0] == 0xC0 && resp[1] == 0x14) {
    Serial.println("OK - Votol responded!");
    Serial.print("  Response: ");
    printHex(resp, len);
    Serial.println();

    float voltage = ((resp[5] << 8) | resp[6]) / 10.0;
    float current = ((int16_t)((resp[7] << 8) | resp[8])) / 10.0;
    int rpm = (resp[14] << 8) | resp[15];
    int ctrlTemp = resp[16] - 50;
    int motorTemp = resp[17] - 50;
    Serial.printf("  V=%.1f A=%.1f RPM=%d CT=%dC MT=%dC\n",
      voltage, current, rpm, ctrlTemp, motorTemp);
  } else if (len > 0) {
    Serial.print("  Got ");
    Serial.print(len);
    Serial.print(" bytes: ");
    printHex(resp, len);
    Serial.println();
  } else {
    Serial.println("FAIL - no response from Votol");
    Serial.println("  Check wiring: GPIO9=RX, GPIO10=TX, GND");
  }
}

// Probe a specific command code and report response
bool probeCmd(uint8_t cmdCode, bool verbose) {
  uint8_t cmd[24];
  // Try with minimal payload
  buildCmd(cmd, cmdCode, NULL, 0);

  uint8_t resp[64];
  int len = sendAndRead(cmd, 24, resp, 64, 300);

  if (len > 0 && !(len == 24 && memcmp(resp, cmd, 24) == 0)) {
    // Got a real response (not just our own echo repeated)
    if (verbose) {
      Serial.printf("  CMD 0x%02X '%c': %d bytes -> ", cmdCode,
        (cmdCode >= 0x20 && cmdCode <= 0x7E) ? cmdCode : '.', len);
      printHex(resp, len);
      Serial.println();
    }
    return true;
  }
  return false;
}

// Probe all command codes to discover what the controller responds to
void probeAllCommands() {
  Serial.println("\n=== PROBING ALL COMMAND CODES ===");
  Serial.println("Trying cmd bytes 0x00-0xFF...");
  Serial.println();

  int found = 0;
  for (int c = 0; c <= 0xFF; c++) {
    if (c % 16 == 0) {
      Serial.printf("  Probing 0x%02X-0x%02X...\r", c, min(c + 15, 0xFF));
    }
    if (probeCmd((uint8_t)c, true)) {
      found++;
    }
    delay(50); // don't hammer the controller
  }
  Serial.printf("\nDone. Found %d responding commands.\n", found);
}

// Try known read commands to dump configuration
void dumpSettings() {
  Serial.println("\n=== DUMP VOTOL SETTINGS ===");
  configLen = 0;

  // First verify connection
  Serial.println("Verifying connection...");
  uint8_t resp[64];
  int len = sendAndRead(SHOW_CMD, sizeof(SHOW_CMD), resp, 64, 500);

  if (len < 24 || resp[0] != 0xC0) {
    Serial.println("ERROR: Cannot connect to Votol. Check wiring.");
    return;
  }
  Serial.println("Connected OK.");

  // Store the SHOW response as part of dump (has calibration data)
  Serial.println("\n--- SHOW (telemetry) ---");
  Serial.print("  ");
  printHex(resp, len);
  Serial.println();
  memcpy(&configDump[configLen], resp, len);
  configLen += len;

  // Try various command codes that might return config
  // Known: 'S'=SHOW, 'P'=Program/write, 'R'=Read?
  uint8_t tryCommands[] = {
    0x50, // 'P' - Parameters
    0x52, // 'R' - Read
    0x43, // 'C' - Config?
    0x44, // 'D' - Data?
    0x47, // 'G' - Get?
    0x49, // 'I' - Info?
    0x4C, // 'L' - List?
    0x4D, // 'M' - Map?
    0x56, // 'V' - Version?
    0x41, // 'A' - All?
    0x42, // 'B' - Backup?
    0x45, // 'E' - EEPROM?
    0x46, // 'F' - Firmware?
    0x54, // 'T' - Table?
    0x55, // 'U' - Upload?
    0x57, // 'W' - Write?
    0x01, 0x02, 0x03, 0x04, 0x05,
    0x10, 0x11, 0x12, 0x13, 0x14, 0x15,
    0x20, 0x21, 0x22, 0x23, 0x24, 0x25,
    0x30, 0x31, 0x32, 0x33, 0x34, 0x35,
    0x80, 0x81, 0x82, 0x83, 0x90, 0x91,
    0xA0, 0xA1, 0xFF
  };
  int numTry = sizeof(tryCommands);

  Serial.println("\n--- Probing config commands ---");
  for (int i = 0; i < numTry; i++) {
    uint8_t cmd[24];
    buildCmd(cmd, tryCommands[i], NULL, 0);
    len = sendAndRead(cmd, 24, resp, 64, 300);

    if (len > 0) {
      Serial.printf("  CMD 0x%02X: %d bytes -> ", tryCommands[i], len);
      printHex(resp, len);
      Serial.println();

      // Store non-duplicate responses
      if (configLen + len + 2 < 512) {
        configDump[configLen++] = tryCommands[i]; // tag byte
        configDump[configLen++] = (uint8_t)len;    // length byte
        memcpy(&configDump[configLen], resp, len);
        configLen += len;
      }
    }
    delay(50);
  }

  // Also try sending SHOW with different byte[2] values (might select different data pages)
  Serial.println("\n--- Probing data pages (byte[2]) ---");
  for (uint8_t page = 0; page <= 0x10; page++) {
    uint8_t cmd[24];
    memcpy(cmd, SHOW_CMD, 24);
    cmd[2] = page;
    cmd[22] = xorChecksum(cmd, 22);
    len = sendAndRead(cmd, 24, resp, 64, 300);

    if (len > 0 && len >= 24) {
      // Check if different from base SHOW response
      bool different = false;
      if (len != 24) different = true;
      else {
        for (int j = 0; j < 24; j++) {
          if (resp[j] != configDump[j]) { different = true; break; }
        }
      }
      if (different || page == 0) {
        Serial.printf("  Page 0x%02X: %d bytes -> ", page, len);
        printHex(resp, len);
        Serial.println();
      }
    }
    delay(50);
  }

  // Output the raw dump in a copyable format
  Serial.println("\n========================================");
  Serial.println("SETTINGS DUMP COMPLETE");
  Serial.println("Copy the line below for restore:");
  Serial.println("========================================");
  Serial.print("SETTINGS_HEX:");
  for (int i = 0; i < configLen; i++) {
    if (configDump[i] < 0x10) Serial.print("0");
    Serial.print(configDump[i], HEX);
  }
  Serial.println();
  Serial.println("========================================");
  Serial.printf("Total %d bytes captured.\n", configLen);
}

// Restore settings from hex string
void restoreSettings() {
  Serial.println("\n=== RESTORE VOTOL SETTINGS ===");
  Serial.println("Paste the SETTINGS_HEX data (without 'SETTINGS_HEX:' prefix),");
  Serial.println("then press Enter:");

  // Wait for hex input
  String hexInput = "";
  unsigned long timeout = millis() + 60000; // 60 second timeout

  while (millis() < timeout) {
    if (Serial.available()) {
      char c = Serial.read();
      if (c == '\n' || c == '\r') {
        if (hexInput.length() > 0) break;
      } else if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f')) {
        hexInput += c;
      }
    }
  }

  if (hexInput.length() < 2) {
    Serial.println("ERROR: No data received.");
    return;
  }

  // Parse hex string to bytes
  int dataLen = hexInput.length() / 2;
  uint8_t* data = (uint8_t*)malloc(dataLen);
  if (!data) {
    Serial.println("ERROR: Out of memory.");
    return;
  }

  for (int i = 0; i < dataLen; i++) {
    char h = hexInput[i * 2];
    char l = hexInput[i * 2 + 1];
    uint8_t hi = (h >= 'a') ? (h - 'a' + 10) : (h >= 'A') ? (h - 'A' + 10) : (h - '0');
    uint8_t lo = (l >= 'a') ? (l - 'a' + 10) : (l >= 'A') ? (l - 'A' + 10) : (l - '0');
    data[i] = (hi << 4) | lo;
  }

  Serial.printf("Parsed %d bytes from hex input.\n", dataLen);
  Serial.print("Data: ");
  printHex(data, min(dataLen, 48));
  if (dataLen > 48) Serial.print("...");
  Serial.println();

  // Skip the first 24 bytes (SHOW response), then parse tagged config blocks
  int pos = 24; // skip SHOW response
  int blockCount = 0;

  while (pos + 2 < dataLen) {
    uint8_t cmdTag = data[pos];
    uint8_t blockLen = data[pos + 1];
    pos += 2;

    if (pos + blockLen > dataLen) break;

    // Only try to write back responses that look like config (24-byte Votol frames)
    if (blockLen == 24 && data[pos] == 0xC0 && data[pos + 1] == 0x14) {
      Serial.printf("\n  Block %d: CMD 0x%02X, %d bytes\n", blockCount, cmdTag, blockLen);
      Serial.print("    Data: ");
      printHex(&data[pos], blockLen);
      Serial.println();

      // Build write command with this data as payload
      // Transform response back to command format
      uint8_t writeCmd[24];
      memcpy(writeCmd, &data[pos], 24);
      writeCmd[0] = 0xC9; // command header (was 0xC0 in response)
      writeCmd[22] = xorChecksum(writeCmd, 22);

      Serial.print("    Write: ");
      printHex(writeCmd, 24);
      Serial.println();

      Serial.println("    Send? (y/n)");

      // Wait for confirmation
      while (!Serial.available()) delay(10);
      char confirm = Serial.read();
      while (Serial.available()) Serial.read(); // flush

      if (confirm == 'y' || confirm == 'Y') {
        uint8_t resp[48];
        int rlen = sendAndRead(writeCmd, 24, resp, 48, 500);
        Serial.print("    Response: ");
        if (rlen > 0) {
          printHex(resp, rlen);
        } else {
          Serial.print("(none)");
        }
        Serial.println();
      } else {
        Serial.println("    Skipped.");
      }
      blockCount++;
    }
    pos += blockLen;
  }

  free(data);
  Serial.printf("\nRestore complete. Processed %d blocks.\n", blockCount);
}

// Send a raw 24-byte command from hex input
void sendRawCommand() {
  Serial.println("\n=== SEND RAW COMMAND ===");
  Serial.println("Enter 24 bytes as hex (48 hex chars), then Enter:");

  String hexInput = "";
  unsigned long timeout = millis() + 30000;

  while (millis() < timeout) {
    if (Serial.available()) {
      char c = Serial.read();
      if (c == '\n' || c == '\r') {
        if (hexInput.length() > 0) break;
      } else if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f')) {
        hexInput += c;
      }
    }
  }

  if (hexInput.length() < 4) {
    Serial.println("ERROR: Too short.");
    return;
  }

  int dataLen = hexInput.length() / 2;
  uint8_t cmd[64];
  for (int i = 0; i < dataLen && i < 64; i++) {
    char h = hexInput[i * 2];
    char l = hexInput[i * 2 + 1];
    uint8_t hi = (h >= 'a') ? (h - 'a' + 10) : (h >= 'A') ? (h - 'A' + 10) : (h - '0');
    uint8_t lo = (l >= 'a') ? (l - 'a' + 10) : (l >= 'A') ? (l - 'A' + 10) : (l - '0');
    cmd[i] = (hi << 4) | lo;
  }

  Serial.print("Sending: ");
  printHex(cmd, dataLen);
  Serial.println();

  uint8_t resp[64];
  int len = sendAndRead(cmd, dataLen, resp, 64, 500);

  if (len > 0) {
    Serial.printf("Response (%d bytes): ", len);
    printHex(resp, len);
    Serial.println();
  } else {
    Serial.println("No response.");
  }
}

void setup() {
  Serial.begin(115200);
  Serial2.begin(VOTOL_BAUD, SERIAL_8N1, VOTOL_RX_PIN, VOTOL_TX_PIN);

  delay(2000);
  Serial.println();
  Serial.println("========================================");
  Serial.println("  Votol Settings Dump & Restore Tool");
  Serial.println("  GPIO9=RX, GPIO10=TX, 9600 baud");
  Serial.println("========================================");
  Serial.println();
  Serial.println("Commands:");
  Serial.println("  t = Test connection (SHOW)");
  Serial.println("  d = Dump all settings");
  Serial.println("  r = Restore settings from hex");
  Serial.println("  p = Probe ALL command codes (slow)");
  Serial.println("  s = Send raw hex command");
  Serial.println();
  Serial.println("Ready. Type a command + Enter.");
}

void loop() {
  if (Serial.available()) {
    char cmd = Serial.read();
    // Flush remaining newline/CR
    delay(10);
    while (Serial.available()) Serial.read();

    switch (cmd) {
      case 't': case 'T':
        testConnection();
        break;
      case 'd': case 'D':
        dumpSettings();
        break;
      case 'r': case 'R':
        restoreSettings();
        break;
      case 'p': case 'P':
        probeAllCommands();
        break;
      case 's': case 'S':
        sendRawCommand();
        break;
      default:
        if (cmd != '\n' && cmd != '\r') {
          Serial.printf("Unknown command: '%c'\n", cmd);
        }
        break;
    }
    Serial.println("\nReady. Type a command + Enter.");
  }
}
