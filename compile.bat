javac -encoding utf-8 ij\ImageJ.java
javac -encoding utf-8 ij\plugin\*.java
javac -encoding utf-8 ij\plugin\filter\*.java
javac -encoding utf-8 ij\plugin\frame\*.java
java -Xmx8g ij.ImageJ %*
