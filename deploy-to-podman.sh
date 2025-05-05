#!/bin/bash

# Obter os IDs dos containers em execução
CONTAINERS=$(podman ps | grep labredes | awk '{print $1}')
CONTAINER_COUNT=0

# Para cada container, copiar os arquivos Java e iniciar a aplicação
for CONTAINER in $CONTAINERS; do
    CONTAINER_COUNT=$((CONTAINER_COUNT + 1))
    DEVICE_NAME="Device${CONTAINER_COUNT}"
    
    echo "Copiando arquivos para container $CONTAINER (${DEVICE_NAME})..."
    
    # Copiar todos os arquivos .java e .class para o container
    podman cp *.java $CONTAINER:/home/
    podman cp *.class $CONTAINER:/home/
    
    echo "Arquivos copiados para o container $CONTAINER."
    echo "Para executar a aplicação no container $CONTAINER, acesse o terminal via interface web e execute:"
    echo "cd /home && java Device ${DEVICE_NAME}"
    echo "---------------------------------------------------"
done

echo "Total de $CONTAINER_COUNT containers configurados."
echo ""
echo "INSTRUÇÕES:"
echo "1. Acesse cada container pela interface web em:"
echo "   - http://localhost:8081/"
echo "   - http://localhost:8082/"
echo "   - http://localhost:8083/"
echo ""
echo "2. Em cada terminal, execute o comando:"
echo "   cd /home && java Device Device<N>"
echo "   (onde <N> é o número do container: 1, 2 ou 3)"
echo ""
echo "3. Para simular problemas de rede, use os comandos tc dentro dos containers."
echo "   Exemplo para simular 20% de perda de pacotes:"
echo "   tc qdisc add dev eth0 root netem loss 20%"
echo ""
echo "4. Para testar a comunicação, use os comandos:"
echo "   - devices (lista dispositivos)"
echo "   - talk <nome> <mensagem> (envia mensagem)"
echo "   - sendfile <nome> <arquivo> (envia arquivo)" 