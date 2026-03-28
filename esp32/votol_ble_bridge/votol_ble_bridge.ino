/*
 * Votol Serial-to-BLE Bridge for VoltronDash
 *
 * Wiring (ESP32-S3):
 *   ESP32 GPIO9  (RX) <- Votol TX
 *   ESP32 GPIO10 (TX) -> Votol RX
 *   ESP32 GND         -  Votol GND
 *
 * SHOW command must include volcalib/curcalib values
 * or the controller reports 0V!
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// --- Pin Config ---
#define VOTOL_RX_PIN 9
#define VOTOL_TX_PIN 10
#define VOTOL_BAUD   9600
#define LED_PIN      2

// --- BLE Config ---
#define DEVICE_NAME  "VOTOL_BLE"
#define SERVICE_UUID "0000ffe0-0000-1000-8000-00805f9b34fb"
#define NOTIFY_UUID  "0000ffe1-0000-1000-8000-00805f9b34fb"
#define WRITE_UUID   "0000ffe2-0000-1000-8000-00805f9b34fb"

#define SHOW_INTERVAL_MS  1000
#define SERIAL_TIMEOUT_MS 100

// SHOW command WITH calibration values:
// B16=0x1E (weak flux cal=30), B17=0xAA
// B18-19=0x04 0x67 (volcalib=1127)
// B20-21=0x00 0xF3 (curcalib=243)
static const uint8_t SHOW_CMD[] = {
  0xC9, 0x14, 0x02, 0x53, 0x48, 0x4F, 0x57, 0x00,
  0x00, 0x00, 0x00, 0x00, 0xAA, 0x00, 0x00, 0x00,
  0x1E, 0xAA, 0x04, 0x67, 0x00, 0xF3, 0x52, 0x0D
};

BLEServer* pServer = NULL;
BLECharacteristic* pNotifyChar = NULL;
bool bleConnected = false;
uint32_t lastShowTime = 0;
uint32_t lastLedToggle = 0;
bool ledState = false;

class ServerCB : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override {
    bleConnected = true;
    digitalWrite(LED_PIN, HIGH);
    Serial.println("BLE connected");
  }
  void onDisconnect(BLEServer* s) override {
    bleConnected = false;
    Serial.println("BLE disconnected");
    delay(100);
    s->startAdvertising();
  }
};

class WriteCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pChar) override {
    String val = pChar->getValue();
    if (val.length() > 0) {
      Serial2.write((const uint8_t*)val.c_str(), val.length());
    }
  }
};

uint8_t xorChecksum(const uint8_t* data, int len) {
  uint8_t c = 0;
  for (int i = 0; i < len; i++) c ^= data[i];
  return c;
}

bool readVotolFrame(uint8_t* frame, int maxWait) {
  uint32_t start = millis();
  int idx = 0;
  bool foundHeader = false;

  while (millis() - start < maxWait) {
    if (Serial2.available()) {
      uint8_t b = Serial2.read();
      if (!foundHeader) {
        if (idx == 0 && b == 0xC0) { frame[idx++] = b; }
        else if (idx == 1 && b == 0x14) { frame[idx++] = b; foundHeader = true; }
        else { idx = 0; }
      } else {
        frame[idx++] = b;
        if (idx >= 24) {
          if (frame[23] == 0x0D) {
            uint8_t calc = xorChecksum(frame, 22);
            if (calc == frame[22]) return true;
          }
          return false;
        }
      }
    }
  }
  return false;
}

void setup() {
  Serial.begin(115200);
  delay(3000);
  Serial.println("\n=== Votol BLE Bridge ===");
  Serial.flush();

  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  Serial2.begin(VOTOL_BAUD, SERIAL_8N1, VOTOL_RX_PIN, VOTOL_TX_PIN);
  Serial.println("Votol serial on GPIO9/10 @ 9600");

  Serial.println("Starting BLE...");
  BLEDevice::init(DEVICE_NAME);
  Serial.println("BLE init done");

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCB());

  BLEService* pSvc = pServer->createService(SERVICE_UUID);
  pNotifyChar = pSvc->createCharacteristic(
    NOTIFY_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  pNotifyChar->addDescriptor(new BLE2902());

  BLECharacteristic* pWriteChar = pSvc->createCharacteristic(
    WRITE_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  pWriteChar->setCallbacks(new WriteCB());

  pSvc->start();

  BLEAdvertising* pAdv = BLEDevice::getAdvertising();
  pAdv->addServiceUUID(SERVICE_UUID);
  pAdv->setScanResponse(true);
  BLEDevice::startAdvertising();

  Serial.println("BLE advertising: VOTOL_BLE");
  Serial.println("Ready!");
}

void loop() {
  uint32_t now = millis();

  if (!bleConnected) {
    if (now - lastLedToggle > 500) {
      ledState = !ledState;
      digitalWrite(LED_PIN, ledState);
      lastLedToggle = now;
    }
  }

  if (now - lastShowTime >= SHOW_INTERVAL_MS) {
    lastShowTime = now;
    while (Serial2.available()) Serial2.read();
    Serial2.write(SHOW_CMD, sizeof(SHOW_CMD));
    Serial2.flush();

    uint8_t frame[24];
    bool gotReal = false;

    for (int attempt = 0; attempt < 2; attempt++) {
      if (readVotolFrame(frame, SERIAL_TIMEOUT_MS)) {
        if (frame[0] == 0xC0) {
          gotReal = true;
          break;
        }
      } else {
        break;
      }
    }

    if (gotReal) {
      if (bleConnected) {
        pNotifyChar->setValue(frame, 24);
        pNotifyChar->notify();
      }
      Serial.printf("RAW: ");
      for (int i = 0; i < 24; i++) Serial.printf("%02X ", frame[i]);
      Serial.println();

      float voltage = ((frame[5] << 8) | frame[6]) / 10.0;
      float current = ((int16_t)((frame[7] << 8) | frame[8])) / 10.0;
      int rpm = (frame[14] << 8) | frame[15];
      int ctrlTemp = frame[16] - 50;
      int motorTemp = frame[17] - 50;
      Serial.printf("V=%.1f A=%.1f RPM=%d CT=%dC MT=%dC %s\n",
        voltage, current, rpm, ctrlTemp, motorTemp,
        bleConnected ? "[BLE]" : "");
    }
  }
}
