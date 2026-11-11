"""Offline training job: `python jobs/train_anomaly_model.py --output-dir models/anomaly`."""

from stream_processor.training import main

if __name__ == "__main__":
    main()
