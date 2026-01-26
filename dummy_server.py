from flask import Flask, jsonify
import yaml

app = Flask(__name__)

@app.route('/config/experiments', methods=['GET'])
def get_experiment():
    # return jsonify({

    # "experiments":[{
    #     'id': "futsal_experiment_9199a",
    #     'description': 'futsal_experiment'
    # },
    # {
    #     'id': "environment_experiment_9198b",
    #     'description': 'environment_experiment'
    # }
    # ]
    # })
    return jsonify(
        [{
        'id': "futsal_experiment_9199a",
        'description': 'futsal_experiment'
    },
    {
        'id': "environment_experiment_9198b",
        'description': 'environment_experiment'
    }
    ]
    )

@app.route('/config/experiments/<id>', methods=['GET'])
def get_experiment_config(id):
    try:
        with open('sample_device_config.yaml', 'r') as file:
            config = yaml.safe_load(file)
        return jsonify(config)
    except FileNotFoundError:
        return jsonify({'error': 'File not found'}), 404
    except yaml.YAMLError as e:
        return jsonify({'error': f'Error parsing YAML file: {e}'}), 500

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5001)
