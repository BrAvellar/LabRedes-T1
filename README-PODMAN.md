# Execução do Sistema de Comunicação P2P com Podman

Este guia explica como executar o sistema de comunicação P2P usando containers Podman.

## Pré-requisitos

- Containers Podman já em execução conforme as instruções:
  ```
  podman run -v /home:/home --cap-add NET_ADMIN --privileged --network lab -p 8081:8080 labredes
  podman run -v /home:/home --cap-add NET_ADMIN --privileged --network lab -p 8082:8080 labredes
  podman run -v /home:/home --cap-add NET_ADMIN --privileged --network lab -p 8083:8080 labredes
  ```

## Passos para Execução

### 1. Compile o código Java localmente

```bash
javac Device.java DeviceInfo.java FileUtils.java MessageHandler.java PendingMessage.java UdpNode.java
```

### 2. Transfira os arquivos para os containers

Você pode usar o script `deploy-to-podman.sh` para copiar os arquivos automaticamente:

```bash
# No Linux/Mac
chmod +x deploy-to-podman.sh
./deploy-to-podman.sh

# No Windows (PowerShell)
# Execute manualmente os comandos de cópia para cada container:
podman cp *.java <container-id>:/home/
podman cp *.class <container-id>:/home/
```

### 3. Execute a aplicação em cada container

Acesse a interface web de cada container:
- Container 1: http://localhost:8081/
- Container 2: http://localhost:8082/
- Container 3: http://localhost:8083/

Em cada terminal do container, execute:
```bash
cd /home
java Device Device1  # Para o container 1
java Device Device2  # Para o container 2
java Device Device3  # Para o container 3
```

## Comandos Disponíveis na Aplicação

- `devices` - Lista todos os dispositivos ativos na rede
- `talk <nome> <mensagem>` - Envia mensagem para outro dispositivo
- `sendfile <nome> <caminho-arquivo>` - Envia arquivo para outro dispositivo

## Simulando Condições Adversas de Rede

Você pode usar o comando `tc` dentro dos containers para simular problemas de rede:

### Perda de pacotes (20%)
```bash
tc qdisc add dev eth0 root netem loss 20%
```

### Atraso (200ms com 50ms de variação)
```bash
tc qdisc add dev eth0 root netem delay 200ms 50ms
```

### Corrupção de pacotes (10%)
```bash
tc qdisc add dev eth0 root netem corrupt 10%
```

### Duplicação de pacotes (15%)
```bash
tc qdisc add dev eth0 root netem duplicate 15%
```

### Remover todas as configurações de simulação
```bash
tc qdisc del dev eth0 root
```

## Capturando o Tráfego de Rede

Instale o tcpdump no container e capture o tráfego:
```bash
apt-get update && apt-get install -y tcpdump
tcpdump -i eth0 -w /home/capture.pcap udp port 9876
```

O arquivo de captura pode ser copiado para o host e aberto no Wireshark:
```bash
podman cp <container-id>:/home/capture.pcap ./capture.pcap
``` 