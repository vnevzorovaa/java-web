pipeline {
    agent any

    environment {
        PROD_HOST   = '139.100.237.220'
        DEPLOY_USER = 'deploy'
        APP_PATH    = '/opt/web/app.jar'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh 'chmod +x mvnw'
                sh './mvnw clean package'
            }
        }

        stage('Deploy') {
            steps {
                withCredentials([sshUserPrivateKey(
                        credentialsId: 'deploy-prod-key',
                        keyFileVariable: 'SSH_KEY',
                        usernameVariable: 'SSH_USER')]) {
                    sh '''
                        set -e
                        JAR=$(ls target/*.jar | grep -v original | head -n1)
                        scp -o StrictHostKeyChecking=no -i "$SSH_KEY" "$JAR" ${SSH_USER}@${PROD_HOST}:${APP_PATH}
                        ssh -o StrictHostKeyChecking=no -i "$SSH_KEY" ${SSH_USER}@${PROD_HOST} "sudo systemctl restart web"
                    '''
                }
            }
        }

        stage('Verify') {
            steps {
                sh '''
                    set -e
                    for i in $(seq 1 10); do
                        if curl -sf http://${PROD_HOST}/actuator/health/readiness | grep -q '"status":"UP"'; then
                            echo "App is ready"
                            break
                        fi
                        echo "Waiting for app to become ready..."
                        sleep 2
                    done
                    curl -sf http://${PROD_HOST}/ -o /dev/null -w "GET / -> HTTP %{http_code}\\n"
                '''
            }
        }
    }
}
