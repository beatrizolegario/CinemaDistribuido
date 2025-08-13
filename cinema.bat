@echo off

REM Define o diretório do projeto e as bibliotecas do ZooKeeper
set PROJECT_DIR=src\main\java
set ZK_DIR="C:\temp\apache-zookeeper-3.9.3-bin"

REM Define o classpath com todas as bibliotecas necessárias
set CP_ZK=.;%ZK_DIR%\lib\zookeeper-3.9.3.jar;%ZK_DIR%\lib\zookeeper-jute-3.9.3.jar;%ZK_DIR%\lib\slf4j-api-1.7.30.jar;%ZK_DIR%\lib\logback-core-1.2.13.jar;%ZK_DIR%\lib\logback-classic-1.2.13.jar;%ZK_DIR%\lib\netty-handler-4.1.113.Final.jar

REM Adiciona o diretório do seu código fonte ao classpath para que o javac o encontre
set CLASSPATH=%CP_ZK%;%PROJECT_DIR%

REM ------------------- INÍCIO DAS MODIFICAÇÕES -------------------

REM Inicia o servidor ZooKeeper em uma nova janela de console.
echo Iniciando o servidor ZooKeeper em uma nova janela...
START "ZooKeeper Server" %ZK_DIR%\bin\zkServer.cmd

REM Adiciona uma pausa para dar tempo ao servidor de inicializar.
echo Aguardando 10 segundos para o servidor ZooKeeper iniciar...
TIMEOUT /T 10 /NOBREAK

REM -------------------- FIM DAS MODIFICAÇÕES ---------------------

echo Compilando CinemaTicketingSystem.java...
javac -cp "%CLASSPATH%" %PROJECT_DIR%\CinemaTicketingSystem.java

REM Verifica se a compilação foi bem-sucedida
if %errorlevel% neq 0 (
    echo A compilação falhou. Verifique os erros acima.
    pause
    exit /b 1
)

echo Compilação concluída com sucesso!
echo Executando o programa...

REM Executa a classe principal
java -cp "%CLASSPATH%" CinemaTicketingSystem

pause
