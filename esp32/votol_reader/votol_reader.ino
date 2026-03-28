/*
 * Votol Reader — Single-wire serial (send request, skip echo, read response)
 * Baud: 9600, GPIO16=RX, GPIO17=TX
 */

#define VOTOL_RX 16
#define VOTOL_TX 17

// SHOW command — requests telemetry
const uint8_t VOTOL_REQUEST[] = {
  0xC9, 0x14, 0x02, 0x53, 0x48, 0x4F, 0x57, 0x00,
  0x00, 0x00, 0x00, 0x00, 0xAA, 0x00, 0x00, 0x00,
  0x1E, 0xAA, 0x04, 0x67, 0x00, 0xF3, 0x52, 0x0D
};

uint8_t rxBuf[48];  // room for echo (24) + response (24)
int rxCount = 0;
unsigned long lastRequest = 0;
unsigned long lastRxByte = 0;
int attempt = 0;

void setup() {
  Serial.begin(115200);
  Serial2.begin(9600, SERIAL_8N1, VOTOL_RX, VOTOL_TX);

  delay(1000);
  Serial.println("================================");
  Serial.println("  Votol Single-Wire Reader");
  Serial.println("  Send SHOW -> skip echo -> read response");
  Serial.println("================================");
  Serial.println();
}

void sendRequest() {
  // Flush any leftover bytes
  while (Serial2.available()) Serial2.read();
  rxCount = 0;

  Serial2.write(VOTOL_REQUEST, sizeof(VOTOL_REQUEST));
  Serial2.flush();  // wait for TX to finish
  lastRequest = millis();
  lastRxByte = millis();
  attempt++;
  Serial.print("[Attempt ");
  Serial.print(attempt);
  Serial.println("] Sent SHOW request...");
}

void loop() {
  // Send request every 2 seconds
  if (millis() - lastRequest > 2000) {
    sendRequest();
  }

  // Read incoming bytes
  while (Serial2.available()) {
    if (rxCount < 48) {
      rxBuf[rxCount++] = Serial2.read();
      lastRxByte = millis();
    } else {
      Serial2.read();  // discard overflow
    }
  }

  // Process when we have a gap (no new bytes for 100ms)
  if (rxCount > 0 && millis() - lastRxByte > 100) {
    Serial.print("  Received ");
    Serial.print(rxCount);
    Serial.println(" bytes total:");

    // Print ALL raw bytes
    Serial.print("  ALL: ");
    for (int i = 0; i < rxCount; i++) {
      if (rxBuf[i] < 0x10) Serial.print("0");
      Serial.print(rxBuf[i], HEX);
      Serial.print(" ");
    }
    Serial.println();

    // If we got more than 24 bytes, the extra is the Votol response
    if (rxCount > 24) {
      Serial.println("  --- ECHO (first 24) ---");
      Serial.print("  ");
      for (int i = 0; i < 24; i++) {
        if (rxBuf[i] < 0x10) Serial.print("0");
        Serial.print(rxBuf[i], HEX);
        Serial.print(" ");
      }
      Serial.println();

      Serial.println("  --- RESPONSE (bytes 24+) ---");
      Serial.print("  ");
      for (int i = 24; i < rxCount; i++) {
        if (rxBuf[i] < 0x10) Serial.print("0");
        Serial.print(rxBuf[i], HEX);
        Serial.print(" ");
      }
      Serial.println();

      // Try to parse response if it's 24 bytes starting with C0 14
      if (rxCount >= 48 && rxBuf[24] == 0xC0 && rxBuf[25] == 0x14) {
        parseResponse(&rxBuf[24]);
      }
    } else if (rxCount == 24) {
      // Only 24 bytes — just echo, no response
      Serial.println("  (only echo, no response from Votol)");
    }
    Serial.println();
    rxCount = 0;
  }
}

void parseResponse(uint8_t* r) {
  float voltage = (r[5] * 256 + r[6]) / 10.0;
  float current = (r[7] * 256 + r[8]) / 10.0;
  int rpm = r[14] * 256 + r[15];
  int ctrlTemp = (int)r[16] - 50;
  int motorTemp = (int)r[17] - 50;

  const char* gears[] = {"L", "M", "H", "S"};
  uint8_t gear = r[20] & 0x03;
  const char* statuses[] = {"IDLE","INIT","START","RUN","STOP","BRAKE","WAIT","FAULT"};
  uint8_t status = r[21] < 8 ? r[21] : 0;

  Serial.println("  *** VOTOL PARSED DATA ***");
  Serial.print("  Voltage:    "); Serial.print(voltage, 1); Serial.println(" V");
  Serial.print("  Current:    "); Serial.print(current, 1); Serial.println(" A");
  Serial.print("  Power:      "); Serial.print(voltage * current, 0); Serial.println(" W");
  Serial.print("  RPM:        "); Serial.println(rpm);
  Serial.print("  Ctrl Temp:  "); Serial.print(ctrlTemp); Serial.println(" C");
  Serial.print("  Motor Temp: "); Serial.print(motorTemp); Serial.println(" C");
  Serial.print("  Gear:       "); Serial.println(gears[gear]);
  Serial.print("  Status:     "); Serial.println(statuses[status]);
}
