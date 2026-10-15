FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN apt-get update \
    && apt-get install --no-install-recommends -y libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 agent \
    && useradd --system --uid 10001 --gid agent --home-dir /home/agent agent \
    && mkdir -p /opt/huggingface \
    && chown -R agent:agent /opt/huggingface

COPY agent/requirements.txt ./requirements.txt
ARG TORCH_CPU_VERSION=2.8.0
RUN python -m pip install --upgrade pip \
    && python -m pip install \
        --index-url https://download.pytorch.org/whl/cpu \
        --extra-index-url https://pypi.org/simple \
        "torch==${TORCH_CPU_VERSION}+cpu" \
    && python -m pip install -r requirements.txt

COPY --chown=agent:agent agent/ ./
RUN chown -R agent:agent /app /opt/huggingface

USER 10001:10001
EXPOSE 5000

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "5000", "--workers", "1", "--timeout-graceful-shutdown", "30"]
