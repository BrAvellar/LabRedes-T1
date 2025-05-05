# Script PowerShell para copiar os arquivos Java para os containers Podman

# Obter os IDs dos containers Podman em execução
$containers = podman ps | Select-String "labredes"
$containerCount = 0

# Para cada container, copiar os arquivos Java e class
foreach ($containerLine in $containers) {
    $containerCount++
    $containerId = ($containerLine -split '\s+')[0]
    $deviceName = "Device$containerCount"
    
    Write-Host "Copiando arquivos para container $containerId ($deviceName)..."
    
    # Copiar todos os arquivos .java e .class para o container
    podman cp *.java ${containerId}:/home/
    podman cp *.class ${containerId}:/home/
    
    Write-Host "Arquivos copiados para o container $containerId."
    Write-Host "Para executar a aplicação no container $containerId, acesse o terminal via interface web e execute:"
    Write-Host "cd /home && java Device $deviceName"
    Write-Host "---------------------------------------------------"
}

Write-Host "Total de $containerCount containers configurados."
Write-Host ""
Write-Host "INSTRUÇÕES:"
Write-Host "1. Acesse cada container pela interface web em:"
Write-Host "   - http://localhost:8081/"
Write-Host "   - http://localhost:8082/"
Write-Host "   - http://localhost:8083/"
Write-Host ""
Write-Host "2. Em cada terminal, execute o comando:"
Write-Host "   cd /home && java Device Device<N>"
Write-Host "   (onde <N> é o número do container: 1, 2 ou 3)"
Write-Host ""
Write-Host "3. Para simular problemas de rede, use os comandos tc dentro dos containers."
Write-Host "   Exemplo para simular 20% de perda de pacotes:"
Write-Host "   tc qdisc add dev eth0 root netem loss 20%"
Write-Host ""
Write-Host "4. Para testar a comunicação, use os comandos:"
Write-Host "   - devices (lista dispositivos)"
Write-Host "   - talk <nome> <mensagem> (envia mensagem)"
Write-Host "   - sendfile <nome> <arquivo> (envia arquivo)" 