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
    {8, 16, 17}, // RIGHT_MOTOR Pins (EnA, IN1, IN2)
    {18, 7, 15}  // LEFT_MOTOR Pins (EnB, IN3, IN4)
};

#define UP 1
#define DOWN 2
#define LEFT 3
#define RIGHT 4
#define SEARCH 5
#define STOP 0

#define RIGHT_MOTOR 0
#define LEFT_MOTOR 1

#define FORWARD 1
#define BACKWARD -1

const char* ssid = "Mycar";
const char* password = "12345678";

AsyncWebServer server(80);
AsyncWebSocket wsCarInput("/CarInput");

int motorSpeed = 120; // Default motor speed (PWM value)- minimum is 85 and maximum is 255

struct CommandLog {
    String command;
    unsigned long duration;
};

std::vector<CommandLog> commandLog;
unsigned long commandStartTime = 0;

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

void logCommandDuration(String command) {
    if (command == "search") {
        return;
    }
    unsigned long currentTime = millis();
    if (!commandLog.empty()) {
        CommandLog &lastCommand = commandLog.back();
        lastCommand.duration = currentTime - commandStartTime;
    }
    commandStartTime = currentTime;
    commandLog.push_back({command, 0});
}

void moveCar(int inputValue) {
    Serial.printf("Got value as %d\n", inputValue);
    String direction;
    switch (inputValue) {
        case UP:
            direction = "up";
            motorSpeed = 80;
            rotateMotor(RIGHT_MOTOR, FORWARD);
            rotateMotor(LEFT_MOTOR, FORWARD);
            break;
        case DOWN:
            direction = "down";
            motorSpeed = 80;
            rotateMotor(RIGHT_MOTOR, BACKWARD);
            rotateMotor(LEFT_MOTOR, BACKWARD);
            break;
        case LEFT:
            direction = "left";
            motorSpeed = 120;
            rotateMotor(RIGHT_MOTOR, FORWARD);
            rotateMotor(LEFT_MOTOR, STOP);
            break;
        case RIGHT:
            direction = "right";
            motorSpeed = 120;
            rotateMotor(RIGHT_MOTOR, STOP);
            rotateMotor(LEFT_MOTOR, FORWARD);
            break;
        case SEARCH:
            direction = "search";
            motorSpeed = 170;
            rotateMotor(RIGHT_MOTOR, BACKWARD);
            rotateMotor(LEFT_MOTOR, FORWARD);
            break;
        case STOP:
            direction = "stop";
            rotateMotor(RIGHT_MOTOR, STOP);
            rotateMotor(LEFT_MOTOR, STOP);
            break;
        default:
            direction = "stop";
            rotateMotor(RIGHT_MOTOR, STOP);
            rotateMotor(LEFT_MOTOR, STOP);
            break;
    }
    logCommandDuration(direction);
}

void moveCarCommand(const std::string &direction, int value) {
    String dir = String(direction.c_str());  // Convert std::string to Arduino String
    logCommandDuration(dir);
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
    } else if (direction == "search") {
        rotateMotor(RIGHT_MOTOR, BACKWARD);
        rotateMotor(LEFT_MOTOR, FORWARD); 
    }

    delay(value); // Move for the specified duration (in milliseconds)
    moveCar(STOP); // Stop after the duration
}

void handleNotFound(AsyncWebServerRequest *request) {
    request->send(404, "text/plain", "File Not Found");
}

void printCommandLog() {
    String path = "";
    for (const auto &log : commandLog) {
        path += log.command + ":" + String(log.duration / 1000.0, 2) + " sec, ";
    }
    Serial.printf("Calculated path: [%s]\n", path.c_str());
    commandLog.clear(); // Clear the log after printing
}

void onCarInputWebSocketEvent(AsyncWebSocket *server, AsyncWebSocketClient *client, AwsEventType type, void *arg, uint8_t *data, size_t len) {
    switch (type) {
        case WS_EVT_CONNECT:
            Serial.printf("WebSocket client #%u connected from %s\n", client->id(), client->remoteIP().toString().c_str());
            commandStartTime = millis(); // Start the command timer
            break;
        case WS_EVT_DISCONNECT:
            Serial.printf("WebSocket client #%u disconnected\n", client->id());
            moveCar(STOP);
            printCommandLog(); // Print the command log on disconnection
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
                Serial.printf("[%s]\n", key.c_str());
                int valueInt = atoi(value.c_str());

                if (key == "MoveCar") {
                    moveCar(valueInt);
                } else if (key == "Speed") {
                    motorSpeed = valueInt; // Adjust motor speed (PWM value)
                } else if (key == "Direction") {
                    moveCarCommand(value, valueInt); // changed key to value for moveCarCommand
                } else if (key == "Distance") {
                    // Assuming value is the distance in meters
                    float distance = atof(value.c_str()); // Convert to float
                    Serial.printf("Distance: %.2f meters\n", distance);
                    // You can add your logic here to handle the distance information
                    // For example, you can trigger actions based on the distance
                    // or store it for later use.
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
