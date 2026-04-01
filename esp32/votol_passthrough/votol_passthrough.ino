/*
 * Votol USB-Serial Passthrough
 *
 * ESP32-S3 acts as USB-to-Serial bridge:
 *   Mac USB  <-->  Votol UART (9600 baud)
 *   GPIO9 = RX (from Votol)
 *   GPIO10 = TX (to Votol)
 *
 * USB CDC on ESP32-S3 is baud-rate independent.
 * Votol controller always runs at 9600 8N1.
 */

#define VOTOL_RX 9
#define VOTOL_TX 10
#define VOTOL_BAUD 9600
#define LED_PIN 2

uint8_t buf[256];

void setup() {
  Serial.begin(9600);  // USB CDC (baud ignored but set for compatibility)
  Serial2.begin(VOTOL_BAUD, SERIAL_8N1, VOTOL_RX, VOTOL_TX);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);
  delay(500);
}

void loop() {
  // USB -> Votol
  int avail = Serial.available();
  if (avail > 0) {
    if (avail > 256) avail = 256;
    int n = Serial.readBytes(buf, avail);
    Serial2.write(buf, n);
    Serial2.flush();
    digitalWrite(LED_PIN, LOW);
  }

  // Votol -> USB
  avail = Serial2.available();
  if (avail > 0) {
    if (avail > 256) avail = 256;
    int n = Serial2.readBytes(buf, avail);
    Serial.write(buf, n);
    Serial.flush();
    digitalWrite(LED_PIN, HIGH);
  }
}
