#include <Arduino.h>
#include <vector>
#include <string>
#include <AsyncTCP.h>
#include <ESPAsyncWebServer.h>
#include <WiFi.h>
#include <sstream>
#include <iostream>

struct MOTOR_PINS {
    int pinEn;
    int pinIN1;
    int pinIN2;
};

std::vector<MOTOR_PINS> motorPins = {
        {3, 7, 15},  // RIGHT_MOTOR Pins (EnA, IN1, IN2)
        {8, 16, 17}  // LEFT_MOTOR Pins (EnB, IN3, IN4)
};

#define UP 1
#define DOWN 2
#define LEFT 3
#define RIGHT 4
#define STOP 0

#define RIGHT_MOTOR 0
#define LEFT_MOTOR 1

#define FORWARD 1
#define BACKWARD -1

const char* ssid = "Mycar";
const char* password = "12345678";

AsyncWebServer server(80);
AsyncWebSocket wsCarInput("/CarInput");

int motorSpeed = 127;  // Default motor speed (PWM value)

void rotateMotor(int motorNumber, int motorDirection) {
    if (motorDirection == FORWARD) {
        digitalWrite(motorPins[motorNumber].pinIN1, HIGH);
        digitalWrite(motorPins[motorNumber].pinIN2, LOW);
        analogWrite(motorPins[motorNumber].pinEn, motorSpeed);
    } else if (motorDirection == BACKWARD) {
        digitalWrite(motorPins[motorNumber].pinIN1, LOW);
        digitalWrite(motorPins[motorNumber].pinIN2, HIGH);
        analogWrite(motorPins[motorNumber].pinEn, motorSpeed);
    } else {
        digitalWrite(motorPins[motorNumber].pinIN1, LOW);
        digitalWrite(motorPins[motorNumber].pinIN2, LOW);
        analogWrite(motorPins[motorNumber].pinEn, 0);
    }
}

void moveCar(int inputValue) {
    Serial.printf("Got value as %d\n", inputValue);
    switch (inputValue) {
        case UP:
            rotateMotor(RIGHT_MOTOR, FORWARD);
            rotateMotor(LEFT_MOTOR, FORWARD);
            break;

        case DOWN:
            rotateMotor(RIGHT_MOTOR, BACKWARD);
            rotateMotor(LEFT_MOTOR, BACKWARD);
            break;

        case LEFT:
            rotateMotor(RIGHT_MOTOR, FORWARD);
            rotateMotor(LEFT_MOTOR, BACKWARD);
            break;

        case RIGHT:
            rotateMotor(RIGHT_MOTOR, BACKWARD);
            rotateMotor(LEFT_MOTOR, FORWARD);
            break;

        case STOP:
            rotateMotor(RIGHT_MOTOR, STOP);
            rotateMotor(LEFT_MOTOR, STOP);
            break;

        default:
            rotateMotor(RIGHT_MOTOR, STOP);
            rotateMotor(LEFT_MOTOR, STOP);
            break;
    }
}

void moveCarCommand(const std::string &direction, int value) {
    if (direction == "right") {
        rotateMotor(RIGHT_MOTOR, BACKWARD);
        rotateMotor(LEFT_MOTOR, FORWARD);
    } else if (direction == "left") {
        rotateMotor(RIGHT_MOTOR, FORWARD);
        rotateMotor(LEFT_MOTOR, BACKWARD);
    } else if (direction == "up") {
        rotateMotor(RIGHT_MOTOR, FORWARD);
        rotateMotor(LEFT_MOTOR, FORWARD);
    } else if (direction == "down") {
        rotateMotor(RIGHT_MOTOR, BACKWARD);
        rotateMotor(LEFT_MOTOR, BACKWARD);
    }

    delay(value);  // Move for the specified duration (in milliseconds)
    moveCar(STOP);  // Stop after the duration
}

void handleNotFound(AsyncWebServerRequest *request) {
    request->send(404, "text/plain", "File Not Found");
}

void onCarInputWebSocketEvent(AsyncWebSocket *server, AsyncWebSocketClient *client, AwsEventType type, void *arg, uint8_t *data, size_t len) {
    switch (type) {
        case WS_EVT_CONNECT:
            Serial.printf("WebSocket client #%u connected from %s\n", client->id(), client->remoteIP().toString().c_str());
            break;
        case WS_EVT_DISCONNECT:
            Serial.printf("WebSocket client #%u disconnected\n", client->id());
            moveCar(STOP);
            break;
        case WS_EVT_DATA: {
            AwsFrameInfo *info = (AwsFrameInfo*)arg;
            if (info->final && info->index == 0 && info->len == len && info->opcode == WS_TEXT) {
                std::string myData = "";
                myData.assign((char *)data, len);
                std::istringstream ss(myData);
                std::string key, value;
                std::getline(ss, key, ',');
                std::getline(ss, value, ',');
                Serial.printf("Key [%s] Value[%s]\n", key.c_str(), value.c_str());
                int valueInt = atoi(value.c_str());

                if (key == "MoveCar") {
                    moveCar(valueInt);
                } else if (key == "Speed") {
                    motorSpeed = valueInt;  // Adjust motor speed (PWM value)
                } else if (key == "Direction") {
                    moveCarCommand(value, valueInt);  // changed key to value for moveCarCommand
                }
            }
            break;
        }
        case WS_EVT_PONG:
        case WS_EVT_ERROR:
            break;
        default:
            break;
    }
}

void setUpPinModes() {
    for (int i = 0; i < motorPins.size(); i++) {
        pinMode(motorPins[i].pinEn, OUTPUT);
        pinMode(motorPins[i].pinIN1, OUTPUT);
        pinMode(motorPins[i].pinIN2, OUTPUT);
    }
    moveCar(STOP);
}

void setup(void) {
    setUpPinModes();
    Serial.begin(115200);

    WiFi.softAP(ssid, password);
    IPAddress IP = WiFi.softAPIP();
    Serial.print("AP IP address: ");
    Serial.println(IP);

    server.onNotFound(handleNotFound);

    wsCarInput.onEvent(onCarInputWebSocketEvent);
    server.addHandler(&wsCarInput);

    server.begin();
    Serial.println("HTTP server started");
}

void loop() {
    wsCarInput.cleanupClients();
}
