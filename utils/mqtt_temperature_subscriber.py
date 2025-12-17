#!/usr/bin/env python3
"""
MQTT Temperature Subscriber
Receives temperature messages from ble/temperature topic
"""

import paho.mqtt.client as mqtt
import json
import datetime

# MQTT Configuration
MQTT_BROKER = "vmi2211704.contaboserver.net"  # Change to your MQTT broker address
MQTT_PORT = 1883
MQTT_TOPIC = "ble/+"
MQTT_TOPIC2 = "ble/dummy/+"
MQTT_USERNAME = "testuser"  # Set if authentication is required
MQTT_PASSWORD = "p0pp1t025"  # Set if authentication is required

def on_connect(client, userdata, flags, rc):
    """Callback for when the client receives a CONNACK response from the server."""
    if rc == 0:
        print(f"Connected to MQTT broker at {MQTT_BROKER}:{MQTT_PORT}")
        # client.subscribe(MQTT_TOPIC)
        # print(f"Subscribed to topic: {MQTT_TOPIC}")
        client.subscribe(MQTT_TOPIC2)
        print(f"Subscribed to topic: {MQTT_TOPIC2}")
    else:
        print(f"Failed to connect, return code {rc}")

def on_message(client, userdata, msg):
    """Callback for when a PUBLISH message is received from the server."""
    timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    topic = msg.topic
    payload = msg.payload.decode('utf-8')

    print(f"[{timestamp}] Topic: {topic}")
    print(f"[{timestamp}] Message: {payload}")

    # Try to parse as JSON if possible
    try:
        data = json.loads(payload)
        print(f"[{timestamp}] Parsed JSON: {data}")
    except json.JSONDecodeError:
        print(f"[{timestamp}] Raw message (not JSON): {payload}")

    print("-" * 50)

def on_disconnect(client, userdata, rc):
    """Callback for when the client disconnects from the server."""
    print(f"Disconnected from MQTT broker with result code: {rc}")

def main():
    """Main function to set up and run the MQTT client."""
    # Create MQTT client
    client = mqtt.Client()

    # Set callbacks
    client.on_connect = on_connect
    client.on_message = on_message
    client.on_disconnect = on_disconnect

    # Set authentication if required
    if MQTT_USERNAME and MQTT_PASSWORD:
        client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)

    try:
        # Connect to broker
        client.connect(MQTT_BROKER, MQTT_PORT, 60)

        # Start the loop to process network traffic and dispatch callbacks
        print("Starting MQTT client...")
        print("Press Ctrl+C to stop")
        client.loop_forever()

    except KeyboardInterrupt:
        print("\nShutting down...")
        client.disconnect()
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    main()