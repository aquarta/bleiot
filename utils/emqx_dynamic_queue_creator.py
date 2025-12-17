import requests
import json
import yaml
from requests.auth import HTTPBasicAuth
import re
from dotenv import load_dotenv
import os

load_dotenv()
# --- Configuration ---
# It's a good practice to keep credentials and configurations at the top or in a separate file.
EMQX_HOST = os.environ.get('EMQX_HOST')
EMQX_API_PORT = os.environ.get('EMQX_API_PORT')

EMQX_API_PORT = os.environ.get('EMQX_API_PORT')

EMQX_USER = os.environ.get('EMQX_USER')

EMQX_PASSWORD = os.environ.get('EMQX_PASSWORD')

YAML_FILE_PATH = os.environ.get('YAML_FILE_PATH')


# --- Setup ---
base_url = f"http://{EMQX_HOST}:{EMQX_API_PORT}"
auth = HTTPBasicAuth(EMQX_USER, EMQX_PASSWORD)
headers = {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
}

def create_emqx_action(action_name, description, influx_write_syntax):
    """Creates an InfluxDB action in EMQX."""
    url = f"{base_url}/api/v5/actions"
    payload = {
        "connector": "Influx1",  # Assuming your connector is named Influx1
        "description": description,
        "enable": True,
        "name": action_name,
        "parameters": {
            "precision": "ms",
            "write_syntax": influx_write_syntax
        },
        "resource_opts": {
            "health_check_interval": "30s"
        },
        "type": "influxdb"
    }
    response = requests.post(url, auth=auth, headers=headers, data=json.dumps(payload))
    print(f"--- Creating Action: {action_name} ---")
    print(f"Status Code: {response.status_code}")
    print(f"Response: {response.text}\n")
    return response.ok

def create_emqx_rule(rule_id, rule_name, description, sql, action_name):
    """Creates a rule in EMQX to link a topic to an action."""
    url = f"{base_url}/api/v5/rules"
    payload = {
        "sql": sql,
        "actions": [
            f"influxdb:{action_name}"
        ],
        "description": description,
        "enable": True,
        "metadata": {},
        "id": rule_id,
        "name": rule_name
    }
    response = requests.post(url, auth=auth, headers=headers, data=json.dumps(payload))
    print(f"--- Creating Rule: {rule_name} ---")
    print(f"Status Code: {response.status_code}")
    print(f"Response: {response.text}\n")
    return response.ok

def main():
    """Main function to read YAML and create EMQX configurations."""
    try:
        with open(YAML_FILE_PATH, 'r') as file:
            config = yaml.safe_load(file)
    except FileNotFoundError:
        print(f"Error: The file {YAML_FILE_PATH} was not found.")
        return
    except yaml.YAMLError as e:
        print(f"Error parsing YAML file: {e}")
        return

    for device_name, device in config.get('devices', {}).items():
        device_short_name = device.get('shortName', '').lower()
        for service in device.get('services', []):
            for char in service.get('characteristics', []):
                if 'structParser' in char:
                    char_name_cleaned = re.sub(r'[^a-z0-9]', '', char.get('name', '').lower())
                    mqtt_topic = char.get('mqttTopic')

                    if not all([device_short_name, char_name_cleaned, mqtt_topic]):
                        print(f"Skipping characteristic in {device_name} due to missing shortName, name, or mqttTopic.")
                        continue

                    # 1. Construct InfluxDB write syntax
                    measure_name = f"{device_short_name}_{char_name_cleaned}"
                    fields = char['structParser'].get('fields', [])
                    influx_fields = ",".join([f"{field['name']}=${{payload.{field['name']}}}i" for field in fields])
                    write_syntax = f"{measure_name},deviceName=${{payload.deviceName}},gatewayName=${{payload.gatewayName}} {influx_fields}"
                    sql = f"SELECT * FROM \"{mqtt_topic}\""

                    # 2. Create Action
                    action_name = f"action_{measure_name}"
                    action_desc = f"InfluxDB action for {device_name} - {char.get('name')}"
                    create_emqx_action(action_name, action_desc, write_syntax)

                    # 3. Create Rule
                    rule_name = f"rule_{measure_name}"
                    rule_id = f"rule_id_{measure_name}"
                    rule_desc = f"Rule for {device_name} - {char.get('name')}"
                    create_emqx_rule(rule_id, rule_name, rule_desc, sql, action_name)

        if 'movesense_whiteboard' in device:
            
            for measure in device['movesense_whiteboard'].get('measures', []):
                measure_name_cleaned = re.sub(r'[^a-z0-9]', '', measure.get('name', '').lower())
                mqtt_topic = measure.get('mqttTopic')

                if not all([device_short_name, measure_name_cleaned, mqtt_topic]):
                    print(f"Skipping measure in {device_name} due to missing shortName, name, or mqttTopic.")
                    continue

                measure_name = f"{device_short_name}_{measure_name_cleaned}"
                action_name = f"action_{measure_name}"
                rule_name = f"rule_{measure_name}"
                rule_id = f"rule_id_{measure_name}"
                rule_desc = f"Rule for {device_name} - {measure.get('name')}"
                action_desc = f"InfluxDB action for {device_name} - {measure.get('name')}"
                print(measure)
                if 'jsonPayloadParser' in measure:
                    # 1. Construct InfluxDB write syntax from jsonPayloadParser
                    fields = measure['jsonPayloadParser'].get('fields', [])
                    
                    influx_fields_parts = []
                    for field in fields:
                        field_name = field['name']
                        field_path = field['path']
                        field_type = field.get('type', 'float')

                        if field_type == 'integer':
                            influx_fields_parts.append(f"{field_name}=${{payload.{field_path}}}i")
                        elif field_type == 'float':
                            influx_fields_parts.append(f"{field_name}=${{payload.{field_path}}}")
                        
                    influx_fields = ",".join(influx_fields_parts)
                    write_syntax = f"{measure_name},deviceName=${{payload.deviceName}},gatewayName=${{payload.gatewayName}} {influx_fields}"
                    sql = f"SELECT * FROM \"{mqtt_topic}\""

                    create_emqx_action(action_name, action_desc, write_syntax)
                    create_emqx_rule(rule_id, rule_name, rule_desc, sql, action_name)

                elif 'jsonArrayParser' in measure:
                    # 1. Construct FOREACH...DO rule and InfluxDB write syntax
                    array_path = measure['jsonArrayParser'].get('arrayPath')
                    array_alias = "sample_item"
                    fields = measure['jsonArrayParser'].get('fields', [])
                    
                    do_parts = ["payload.deviceName as deviceName", "payload.gatewayName as gatewayName"]
                    influx_fields_parts = []

                    for field in fields:
                        field_name = field['name']
                        field_type = field.get('type', 'integer')
                        field_path = field.get('path')

                        if field_path:
                            do_parts.append(f"{array_alias}.{field_path} as {field_name}")
                        else:
                            do_parts.append(f"{array_alias} as {field_name}")
                        
                        if field_type == 'integer':
                            influx_fields_parts.append(f"{field_name}=${{{field_name}}}i")
                        elif field_type == 'float':
                            influx_fields_parts.append(f"{field_name}=${{{field_name}}}")

                    sql = f"FOREACH payload.{array_path} as {array_alias} DO {', '.join(do_parts)} FROM \"{mqtt_topic}\""
                    influx_fields = ",".join(influx_fields_parts)
                    write_syntax = f"{measure_name},deviceName=${{deviceName}},gatewayName=${{gatewayName}} {influx_fields}"

                    create_emqx_action(action_name, action_desc, write_syntax)
                    create_emqx_rule(rule_id, rule_name, rule_desc, sql, action_name)
main()