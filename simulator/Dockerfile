FROM python:3.12-slim

WORKDIR /app

COPY pyproject.toml ./
RUN pip install --no-cache-dir -e .

COPY main.py ./

EXPOSE 18080
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "18080"]
