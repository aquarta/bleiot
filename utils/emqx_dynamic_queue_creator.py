import requests
import json
import yaml
from requests.auth import HTTPBasicAuth
import re
from dotenv import load_dotenv
import os
import logging

logging.basicConfig(filename="emqx_config.log",level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# Load environment variables from .env file


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


def get_emqx_list_actions():
    list_url = f"{base_url}/api/v5/actions"
    list_response = requests.get(list_url, auth=auth, headers=headers)
    logging.info(f"--- Current Configured actions ---")
    logging.info(f"Status Code: {list_response.status_code}")
    logging.info(f"Response: {list_response.text}\n")
    for action in list_response.json():
        
        logging.info(f"--- Action {action['name']} ---")
    return list_response.json()

def get_emqx_action():
    list_url = f"{base_url}/api/v5/actions"
    list_response = requests.get(list_url, auth=auth, headers=headers)
    logging.info(f"--- Current Configured actions ---")
    logging.info(f"Status Code: {list_response.status_code}")
    logging.info(f"Response: {list_response.text}\n")
    for action in list_response.json():
        
        logging.info(f"--- Action {action['name']} ---")
        logging.info(f"--- Action {action['name']} ---")
    return list_response.json()

def create_emqx_action(action_name, description, influx_write_syntax, current_actions = None):
    """Creates an InfluxDB action in EMQX."""
    if current_actions is None:
        current_actions = []

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
    action_found = any([action for action in current_actions if action['name'] == action_name])
    
    if action_found:
        payload["created_at"] = [action["created_at"] for action in current_actions if action['name'] == action_name][0]
        payload["last_modified_at"] = [action["last_modified_at"] for action in current_actions if action['name'] == action_name][0]
        update_url = url+"/"+"influxdb:"+action_name
        _ = payload.pop("name")
        _ = payload.pop("type")

        response = requests.put(update_url, auth=auth, headers=headers, data=json.dumps(payload))        
        logging.info(f"--- Updating Action: {action_name} --> {update_url} {json.dumps(payload)}---")
    else:
        response = requests.post(url, auth=auth, headers=headers, data=json.dumps(payload))
        logging.info(f"--- Creating Action: {action_name} ---")
    logging.info(f"Status Code: {response.status_code}")
    logging.info(f"Response: {response.text}\n")
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
    action_list = get_emqx_list_actions()

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
                    fixed_fields = [
                        {"name":"gatewayBattery"},
                        {"name":"rssi"},
                    ]
                    fields += fixed_fields
                    influx_fields = ",".join([f"{field['name']}=${{payload.{field['name']}}}i" for field in fields])
                    write_syntax = f"{measure_name},appTagName=${{payload.APP_TAG_NAME}},deviceAddress=${{payload.deviceAddress}},deviceAddress=${{payload.deviceAddress}},deviceName=${{payload.deviceName}},gatewayName=${{payload.gatewayName}} {influx_fields}"
                    sql = f"SELECT * FROM \"{mqtt_topic}\""

                    # 2. Create Action
                    action_name = f"action_{measure_name}"
                    action_desc = f"InfluxDB action for {device_name} - {char.get('name')}"
                    create_emqx_action(action_name, action_desc, write_syntax, action_list)

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
                    print(f"Skipping measure in {device_name} due to missing shortName, name, or mqttTopic.{[device_short_name, measure_name_cleaned, mqtt_topic, measure]}")
                    continue

                measure_name = f"{device_short_name}_{measure_name_cleaned}"
                action_name = f"action_{measure_name}"
                rule_name = f"rule_{measure_name}"
                rule_id = f"rule_id_{measure_name}"
                rule_desc = f"Rule for {device_name} - {measure.get('name')}"
                action_desc = f"InfluxDB action for {device_name} - {measure.get('name')}"
                print(measure)
                if 'jsonPayloadParser' in measure:
                    # 1. Construct SELECT rule and InfluxDB write syntax from jsonPayloadParser
                    parser_config = measure['jsonPayloadParser']
                    fields = parser_config.get('fields', [])
                    fixed_fields = [
                        {"name":"gatewayBattery", "type":"integer"},
                    ]
                    fields += fixed_fields
                    use_jq = parser_config.get('use_jq', False)

                    select_parts = ["payload.deviceName as deviceName", "payload.gatewayName as gatewayName"]
                    influx_fields_parts = []
                    
                    for field in fields:
                        field_name = field['name']
                        field_path = field.get('path')
                        field_type = field.get('type', 'float')

                        if use_jq:
                            # Use first(jq(...)) syntax for complex JSON extraction
                            select_parts.append(f"first(jq('.{field_path}', payload)) as {field_name}")
                        else:
                            # Original logic for direct payload access
                            if field_path:
                                select_parts.append(f"payload.{field_path} as {field_name}")
                            else:
                                select_parts.append(f"payload as {field_name}")

                        if field_type == 'integer':
                            influx_fields_parts.append(f"{field_name}=${{{field_name}}}i")
                        elif field_type == 'float':
                            influx_fields_parts.append(f"{field_name}=${{{field_name}}}")

                    sql = f"SELECT {', '.join(select_parts)} FROM \"{mqtt_topic}\""
                    influx_fields = ",".join(influx_fields_parts)
                    write_syntax = f"{measure_name},deviceName=${{deviceName}},gatewayName=${{gatewayName}} {influx_fields}"

                    create_emqx_action(action_name, action_desc, write_syntax, action_list)
                    create_emqx_rule(rule_id, rule_name, rule_desc, sql, action_name)

                elif 'jsonArrayParser' in measure:
                    # 1. Construct FOREACH...DO rule and InfluxDB write syntax
                    array_path = measure['jsonArrayParser'].get('arrayPath')
                    array_alias = "sample_item"
                    fields = measure['jsonArrayParser'].get('fields', [])
                    
                    do_parts = ["payload.deviceName as deviceName", "payload.gatewayName as gatewayName"]
                    influx_fields_parts = []

                    for field in fields:
                        print(field)
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

                    create_emqx_action(action_name, action_desc, write_syntax, action_list)
                    create_emqx_rule(rule_id, rule_name, rule_desc, sql, action_name)
                
                elif 'SingleMeasurementParser' in measure:
                    parser_config = measure['SingleMeasurementParser']
                    fields = parser_config
                    
                    select_parts = ["payload.deviceName as deviceName", "payload.gatewayName as gatewayName"]
                    influx_fields_parts = []
                    
                    for field in fields:
                        field_name = field['name']
                        field_path = field.get('path')
                        field_type = field.get('type', 'float')

                        if field_path:
                            select_parts.append(f"payload.{field_path} as {field_name}")
                        else:
                            select_parts.append(f"payload as {field_name}")

                        if field_type == 'integer':
                            influx_fields_parts.append(f"{field_name}=${{{field_name}}}i")
                        elif field_type == 'float':
                            influx_fields_parts.append(f"{field_name}=${{{field_name}}}")

                    sql = f"SELECT {', '.join(select_parts)} FROM \"{mqtt_topic}\""
                    influx_fields = ",".join(influx_fields_parts)
                    write_syntax = f"{measure_name},deviceName=${{deviceName}},gatewayName=${{gatewayName}} {influx_fields}"

                    create_emqx_action(action_name, action_desc, write_syntax, action_list)
                    create_emqx_rule(rule_id, rule_name, rule_desc, sql, action_name)

main()