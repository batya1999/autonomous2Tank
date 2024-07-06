# WiFi Tank Control with ESP32

This project allows you to control a tank using an ESP32 via a WebSocket connection. The tank can be moved in various directions and its speed can be adjusted through a web interface or an Android app.

## Features

- Remote control of the tank via WebSocket.
- Direction control: Up, Down, Left, Right.
- Speed control through PWM.
- Automatic path execution with predefined commands.
- Simple, responsive web interface for control.

## Hardware Requirements

- ESP32
- Motor Driver (e.g., L298N)
- Motors
- Power Supply
- WiFi Network

## Pin Configuration

- **RIGHT_MOTOR**: Pins (EnA: 22, IN1: 7, IN2: 15)
- **LEFT_MOTOR**: Pins (EnB: 23, IN3: 16, IN4: 17)

## Software Requirements

- Arduino IDE
- ESP32 Board Library
- AsyncTCP Library
- ESPAsyncWebServer Library

## Installation

1. **Clone the repository:**
   ```sh
   git clone https://github.com/batya1999/autonomous2Tank.git

