// 仓库根不再是构建工程，只当「容器」：放 wrapper、LICENSE、libs/、versions.properties，
// 以及各版本外壳目录。真正能构建的是外壳（每个外壳都是独立 build root，自带 settings）：
//
//   ./gradlew -p neoforge-1.21.1 build
//   ./gradlew -p forge-1.20.1    build
//   ./gradlew -p fabric-1.21.1   build      ← 将来
//
// 共享核心在 common/，由外壳用 srcDir 叠进来，本身不单独构建。
// 版本号唯一来源：./versions.properties。
rootProject.name = "yardsite"

println("[yardsite] 仓库根不产出 jar。用 ./gradlew -p <外壳目录> build，例如 -p neoforge-1.21.1")
