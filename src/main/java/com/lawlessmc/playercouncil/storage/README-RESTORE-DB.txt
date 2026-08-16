Concatenate the parts in order to restore the full DatabaseManager.java:

```bash
cd src/main/java/com/lawlessmc/playercouncil/storage
cat DatabaseManager.java.part1 DatabaseManager.java.part2 DatabaseManager.java.part3 DatabaseManager.java.part4 > DatabaseManager.java
rm -f DatabaseManager.java.part* README-RESTORE-DB.txt
```

Then:
```bash
mvn clean package
```
