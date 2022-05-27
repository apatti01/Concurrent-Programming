import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.BreakIterator;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
This is the exam for DM563 - Concurrent Programming, Spring 2022.
Your task is to implement the following methods of class Exam:
- findWordsCommonToAllLines;
- longestLine;
- wordWithVowels;
- wordsEndingWith.
These methods search text files for particular words.
You must use a BreakIterator to identify words in a text file,
which you can obtain by calling BreakIterator.getWordInstance().
For more details on the usage of BreakIterator, please see the corresponding video lecture in the
course.
The implementations of these methods must exploit concurrency to achieve improved performance.
The only code that you can change is the implementation of these methods.
In particular, you cannot change the signatures (return type, name, parameters) of any method, and
you cannot edit method main.
The current code of these methods throws an UnsupportedOperationException: remove that line before
proceeding on to the implementation.
You can find a complete explanation of the exam rules at the following webpage.
https://github.com/fmontesi/cp2022/tree/main/exam
*/
public class Exam {
    // Do not change this method
    public static void main(String[] args) {
        checkArguments(args.length > 0,
                "You must choose a command: help, allLines, longestLine, vowels, or suffix.");
        switch (args[0]) {
            case "help":
                System.out.println(
                        "Available commands: help, allLines, longestLine, vowels, or suffix.\nFor example, try:\n\tjava Exam allLines data");
                break;
            case "allLines":
                checkArguments(args.length == 2, "Usage: java Exam.java allLines <directory>");
                List<LocatedWord> uniqueWords = findWordsCommonToAllLines(Paths.get(args[1]));
                System.out.println("Found " + uniqueWords.size() + " words");
                uniqueWords.forEach(locatedWord ->
                        System.out.println(locatedWord.word + ":" + locatedWord.filepath));
                break;
            case "longestLine":
                checkArguments(args.length == 2, "Usage: java Exam.java longestLine <directory>");
                Location location = longestLine(Paths.get(args[1]));
                System.out.println("Line with highest number of letters found at " + location.filepath + ":" + location.line);
                break;
            case "vowels":
                checkArguments(args.length == 3, "Usage: java Exam.java vowels <directory> <vowels>");
                int vowels = Integer.parseInt(args[2]);
                Optional<LocatedWord> word = wordWithVowels(Paths.get(args[1]), vowels);
                word.ifPresentOrElse(
                        locatedWord -> System.out.println("Found " + locatedWord.word + " in " + locatedWord.filepath),
                        () -> System.out.println("No word found with " + args[2] + " vowels."));
                break;
            case "suffix":
                checkArguments(args.length == 4, "Usage: java Exam.java suffix <directory> <suffix> <length>");
                int length = Integer.parseInt(args[3]);
                List<LocatedWord> words = wordsEndingWith(Paths.get(args[1]), args[2], length);
                if (words.size() > length) {
                    System.out.println("WARNING: Implementation of wordsEndingWith computes more than " + args[3] + " words!");
                }
                words.forEach(loc -> System.out.println(loc.word + ":" + loc.filepath));
                break;
            default:
                System.out.println("Unrecognised command: " + args[0] + ". Try java Exam.java help.");
                break;
        }
    }

    // Do not change this method
    private static void checkArguments(Boolean check, String message) {
        if (!check) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Returns the words that appear on every line of a text file contained in the given directory.
     * <p>
     * This method recursively visits a directory to find text files contained in it
     * and its subdirectories (and the subdirectories of these subdirectories,
     * etc.).
     * <p>
     * You must consider only files ending with a ".txt" suffix. You are guaranteed
     * that they will be text files.
     * <p>
     * The method should return a list of LocatedWord objects (defined by the class
     * at the end of this file), where each LocatedWord object should consist of:
     * - a word appearing in every line of a file
     * - the path to the file containing such word.
     * <p>
     * All words appearing on every line of some file must appear in the list: words
     * that can be in the list must be in the list.
     * <p>
     * Words must be considered equal without considering differences between
     * uppercase and lowercase letters. For example, the words "Hello", "hEllo" and
     * "HELLo" must be considered equal to the word "hello".
     *
     * @param dir the directory to search
     * @return a list of words that, within a file inside dir, appear on every line
     */
    private static List<LocatedWord> findWordsCommonToAllLines(Path dir) {
        // long t1 = System.currentTimeMillis();
        
        List<LocatedWord> wordsCommonToAllLines = new ArrayList<>(); // List of all the LocatedWords that appear in all the lines

        ExecutorService executor = Executors.newWorkStealingPool(); // Contains a pool of available threads
        ExecutorCompletionService<List<LocatedWord>> completionService = new ExecutorCompletionService<>(executor); // Used to manage the tasks of the executor

        try {
            long pendingTasks = Files.walk(dir) // Walks through the directory
                    //.parallel()
                    .filter(Files::isRegularFile) // Checks whether is a regular file
                    .filter(filePath -> filePath.toString().endsWith(".txt")) // Checks whether is a txt file
                    .map(filePath ->
                            completionService.submit(() -> computeWordsCommonToAllLines(filePath))) // Assign each filePath to a new task (thread)
                    .count(); // Counts the pending tasks

            while (pendingTasks > 0) { // For each task
                wordsCommonToAllLines.addAll(completionService.take().get()); // Add the words that have been found to the list from the task
                pendingTasks--; // Task is completed
            }
        } catch (InterruptedException | ExecutionException | IOException exception) { // If an error occurs
            exception.printStackTrace(); // Prints the error
        }
        try { // Tries to shut down the executor
            executor.shutdown(); // Shutdowns the executor
            executor.awaitTermination(1, TimeUnit.DAYS); // Waits for the executor to terminate
        } catch (InterruptedException exception) { // If an error occurs
            exception.printStackTrace(); // Prints the error
        }
        // long t2 = System.currentTimeMillis();
        // System.out.println("Elapsed time: " + (t2 - t1) + "ms");
        
        return wordsCommonToAllLines; // Returns the list with the words that exist in all the lines of each text
    }

    /**
     * Returns the line with the highest number of letters among all the lines
     * present in the text files contained in a directory.
     * <p>
     * This method recursively visits a directory to find all the text files
     * contained in it and its subdirectories (and the subdirectories of these
     * subdirectories, etc.).
     * <p>
     * You must consider only files ending with a ".txt" suffix. You are
     * guaranteed that they will be text files.
     * <p>
     * The method should return the longest line (counting only letters) found among all text files.
     * If multiple lines are identified as longest, the method should return
     * the one that belongs to the file whose name precedes the filename of the other longest line
     * lexicographically, or if the filename is the same, the line which comes first in the file.
     * To compare strings lexicographically, you can use String::compareTo.
     * See also https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/String.html#compareTo(java.lang.String)
     *
     * @param dir the directory to search
     * @return the line with the highest number of letters found among all text files inside of dir
     */
    private static Location longestLine(Path dir) {
        // long t1 = System.currentTimeMillis();
        
        AtomicReference<Location> longestLine = new AtomicReference<>(); // Atomic reference that indicates the Location of the longest line
        AtomicInteger maxChars = new AtomicInteger(-1); // AtomicInteger that contains the num of the chars of the longest line, initially set to -1 as no line has been found

        ExecutorService executor = Executors.newWorkStealingPool(); // Contains a pool of available threads
        ExecutorCompletionService<List<Object>> completionService = new ExecutorCompletionService<>(executor); // Used to manage the tasks of the executor

        try {
            long pendingTasks = Files.walk(dir) // Walks through the directory
                    //.parallel()
                    .filter(Files::isRegularFile) // Checks whether is a regular file
                    .filter(filePath -> filePath.toString().endsWith(".txt")) // Checks whether is a txt file
                    .map(filePath ->
                            completionService.submit(() -> computeLongestLine(filePath))) // Assign each filePath to a new task (thread)
                    .count(); // Counts the pending tasks

            while (pendingTasks > 0) { // For each task

                try {
                    // Gets the longest line of the file in the form of a List<Object> where:
                    // -> index 0: Integer that indicates the num of char of the longest line
                    // -> index 1: Location that indicates the location of the longest line
                    List<Object> longestLineOfEachFile = completionService.take().get();

                    if ((int) longestLineOfEachFile.get(0) > maxChars.get()) { // If a new longest line is found
                        maxChars.set((int) longestLineOfEachFile.get(0)); // Updates the value with the new amount of chars
                        longestLine.set((Location) longestLineOfEachFile.get(1)); // Updates the location the longest line
                    }

                    else if ((int) longestLineOfEachFile.get(0) == maxChars.get()) { // Otherwise, if the same amount of chars are found in the 2 lines, then
                        longestLine.set(compareStringsLexicographically(longestLine.get(), (Location) longestLineOfEachFile.get(1))); // Updates the location the longest line that has the path the precedes the other lexicographically
                        maxChars.set((int) longestLineOfEachFile.get(0)); // Updates the value with the new amount of chars
                    }

                } catch (InterruptedException | ExecutionException exception) { // If an error occurs
                    exception.printStackTrace(); // Prints the error
                }

                pendingTasks--; // Task is completed
            }
        } catch (IOException exception) { // If an error occurs
            exception.printStackTrace(); // Prints the error
        }
        try { // Tries to shut down the executor
            executor.shutdown(); // Shutdowns the executor
            executor.awaitTermination(1, TimeUnit.DAYS); // Waits for the executor to terminate
        } catch (InterruptedException exception) { // If an error occurs
            exception.printStackTrace(); // Prints the error
        }
        // long t2 = System.currentTimeMillis();
        // System.out.println("Elapsed time: " + (t2 - t1) + "ms");

        return longestLine.get(); // Returns the location of the longest line
    }

    /**
     * Returns an Optional<LocatedWord> (see below) about a word found in the files
     * of the given directory containing the given number of vowels.
     * <p>
     * This method recursively visits a directory to find text files contained in it
     * and its subdirectories (and the subdirectories of these subdirectories,
     * etc.).
     * <p>
     * You must consider only files ending with a ".txt" suffix. You are guaranteed
     * that they will be text files.
     * <p>
     * The method should return an (optional) LocatedWord object (defined by the
     * class at the end of this file), consisting of:
     * - the word found that contains as many vowels as specified by the parameter n (and no more);
     * - the path to the file containing the word.
     * <p>
     * You can consider a letter to be a vowel according to either English or Danish.
     * <p>
     * If a word satisfying the description above can be found, then the method
     * should return an Optional containing the desired LocatedWord. Otherwise, if
     * such a word cannot be found, the method should return Optional.empty().
     * <p>
     * This method should return *as soon as possible*: as soon as a satisfactory
     * word is found, the method should return a result without waiting for the
     * processing of remaining files and/or other data.
     *
     * @param dir    the directory to search
     * @param vowels the number of vowels the word must contain
     * @return an optional LocatedWord about a word containing exactly n vowels
     */
    private static Optional<LocatedWord> wordWithVowels(Path dir, int vowels) {
        // long t1 = System.currentTimeMillis();

        AtomicReference<Optional<LocatedWord>> wordWithVowels = new AtomicReference<>(Optional.empty()); // LocatedWord containing the word with the requested amount of vowels, initially empty (Optional.empty())
        AtomicBoolean found = new AtomicBoolean(false); // Boolean to check if the wordWithVowel has been found

        ExecutorService executor = Executors.newWorkStealingPool(); // Contains a pool of available threads
        ExecutorCompletionService<Optional<LocatedWord>> completionService = new ExecutorCompletionService<>(executor); // Used to manage the tasks of the executor

        try {
            long pendingTasks = Files.walk(dir) // Walks through the directory
                    //.parallel()
                    .filter(Files::isRegularFile) // Checks whether is a regular file
                    .filter(filePath -> filePath.toString().endsWith(".txt")) // Checks whether is a txt file
                    .map(filePath ->
                            completionService.submit(() -> computeWordWithVowels(filePath, vowels))) // Assign each filePath to a new task (thread)
                    .count(); // Counts the pending tasks

            while (pendingTasks > 0 && !found.get()) { // While there are still tasks that have not completed yet AND the word has not been found yet

                Optional<LocatedWord> word = completionService.take().get(); // Gets the result of the task

                if (!word.get().word.equals("")) { // If the word is not empty
                    wordWithVowels.set(Optional.of(new LocatedWord(word.get().word, word.get().filepath))); // Set the value of wordWithVowel to have the value of the found word
                    found.set(true); // Set the boolean to true as the word has been found
                }

                pendingTasks--; // Task is completed
            }
        } catch (InterruptedException | ExecutionException | IOException exception) { // If an error occurs
            exception.printStackTrace(); // Prints the error
        }
        try { // Tries to shut down the executor
            executor.shutdown(); // Shutdowns the executor
            executor.awaitTermination(1, TimeUnit.DAYS); // Waits for the executor to terminate
        } catch (InterruptedException exception) { // If an error occurs
            exception.printStackTrace(); // Prints the error
        }
        // long t2 = System.currentTimeMillis();
        // System.out.println("Elapsed time: " + (t2 - t1) + "ms");

        return wordWithVowels.get(); // Returns the LocatedWord
    }

    /**
     * Returns a list of words found in the given directory ending with the given suffix.
     * <p>
     * This method recursively visits a directory to find text files
     * contained in it and its subdirectories (and the subdirectories of these
     * subdirectories, etc.).
     * <p>
     * You must consider only files ending with a ".txt" suffix. You are
     * guaranteed that they will be text files.
     * <p>
     * The method should return a list of LocatedWord objects (defined by the
     * class at the end of this file), consisting of:
     * - the word found that ends with the given suffix;
     * - the path to the file containing the word.
     * <p>
     * The size of the returned list must not exceed the given limit.
     * Therefore, this method should return *as soon as possible*: if the list
     * reaches the given limit at any point during the computation, no more
     * elements should be added to the list and remaining files and/or other lines
     * should not be analysed.
     *
     * @param dir    the directory to search
     * @param suffix the suffix to be searched for
     * @param limit  the size limit for the returned list
     * @return a list of locations where the given suffix has been found
     */
    private static List<LocatedWord> wordsEndingWith(Path dir, String suffix, int limit) {
        // long t1 = System.currentTimeMillis();

        List<LocatedWord> wordsEndingWith = new ArrayList<>(); // List of all the LocatedWords that end with the requested suffix
        AtomicBoolean found = new AtomicBoolean(false); // Boolean that indicates if the word has been found

        ExecutorService executor = Executors.newWorkStealingPool(); // Contains a pool of available threads
        ExecutorCompletionService<List<LocatedWord>> completionService = new ExecutorCompletionService<>(executor); // Used to manage the tasks of the executor

        try {
            long pendingTasks = Files.walk(dir) // Walks through the directory
                    //.parallel()
                    .filter(Files::isRegularFile) // Checks whether is a regular file
                    .filter(filePath -> filePath.toString().endsWith(".txt")) // Checks whether is a txt file
                    .map(filePath ->
                            completionService.submit(() -> computeWordsEndingWith(filePath, suffix, limit))) // Create a new thread & execute the following code
                    .count(); // Counts the pending tasks

            while (pendingTasks > 0 && !found.get()) { // While there are still tasks that have not completed yet AND the word has not been found yet

                if (wordsEndingWith.size() <= limit) { // If not enough words with the suffix have been found
                    wordsEndingWith.addAll(completionService.take().get()); // Add all the words that have been found from the current task to the list
                } else { // Otherwise
                    found.set(true); // Set the value of the boolean to true as all the words have been found
                    break; // and strop the while loop
                }

                pendingTasks--; // Task is completed
            }
        } catch (InterruptedException | ExecutionException | IOException exception) { // If an error occurs
            exception.printStackTrace(); // Prints the error
        }
        try { // Tries to shut down the executor
            executor.shutdown(); // Shutdowns the executor
            executor.awaitTermination(1, TimeUnit.DAYS); // Waits for the executor to terminate
        } catch (InterruptedException exception) { // If an error occurs
            exception.printStackTrace(); // Prints the error
        }

        // long t2 = System.currentTimeMillis();
        // System.out.println("Elapsed time: " + (t2 - t1) + "ms");

        if (wordsEndingWith.size() > 0) // If there are words in the list
            return wordsEndingWith.subList(0, Math.min(limit, wordsEndingWith.size())); // return the words, but make sure that no more words than the limit are returned.

        return wordsEndingWith; // Otherwise, return the empty list, as there are no words in it.
    }

    // Do not change this class
    private static class LocatedWord {
        private final String word; // the word
        private final Path filepath; // the file where the word has been found

        private LocatedWord(String word, Path filepath) {
            this.word = word;
            this.filepath = filepath;
        }
    }

    // Do not change this class
    private static class Location {
        private final Path filepath; // the file where the word has been found
        private final int line; // the line number at which the word has been found

        private Location(Path filepath, int line) {
            this.filepath = filepath;
            this.line = line;
        }
    }

    // Do not change this class
    private static class InternalException extends RuntimeException {
        private InternalException(String message) {
            super(message);
        }
    }

    /********************************* Used in method wordsCommonToAllLines() ****************************************/

    /**
     * Method that is used in wordsCommonToAllLines() to find the common words of each file
     * @param dir directory of the file
     * @return List<LocatedWord> with all the common words
     */
    private static List<LocatedWord> computeWordsCommonToAllLines(Path dir) {

        List<LocatedWord> wordsCommonToAllLinesOfFile = new ArrayList<>(); // List of all the LocatedWords that appear in all the lines

        try {
            Optional<List<String>> words = Files.lines(dir) // Reads the lines of the text file
                    //.parallel()
                    .filter(line -> !line.isBlank()) // Filters out the lines that are blank as we don't care about them
                    .map(Exam::extractWords) // Transforms Stream<String> to Stream<List<String>> where it contains lines and all the words for each line
                    .reduce((line1, line2) -> { // Finds the commonWords
                        line2.retainAll(line1); // Intersection of the two lines
                        return line2; // Returns one line instead of two, that has all the words that appear in both of the lines
                    });

            if (words.isPresent()) // If there are common words
                for (String w : words.get()) // For each of the word that is found
                    if(!wordIsDuplicate(w,wordsCommonToAllLinesOfFile)) // If the word is not a duplicate
                        wordsCommonToAllLinesOfFile.add(new LocatedWord(w, dir)); // Add the word in the form of LocatedWord to the list

        } catch (IOException exception) { // If an error occurs
            exception.printStackTrace(); // Prints the error
        }

        return wordsCommonToAllLinesOfFile; // Returns the requested list
    }

    /**
     * Method to check if a list contains a word more than one time.
     *
     * @param w word
     * @param wordsCommonToAllLines list
     * @return True or False
     */
    private static boolean wordIsDuplicate(String w,List<LocatedWord> wordsCommonToAllLines) {

        AtomicBoolean wordIsDuplicate = new AtomicBoolean(false); //Boolean that looks out for duplicates

        for (LocatedWord locatedWord : wordsCommonToAllLines) // For every word that already is in list wordsCommonToAllLines
            if (locatedWord.word.equals(w)){ // If a word already exists (is a duplicate)
                wordIsDuplicate.set(true); // Sets duplicate to true
                break; // Stops the for loop
            }

        return wordIsDuplicate.get(); // Return result
    }


    /********************************* Used in method longestLine() *************************************************/

    /**
     * Method that is used in longestLine() to find the longest line of the file
     * @param dir directory of the file
     * @return longest line of the file in the form of a List<Object> where:
     *                 index 0: Integer that indicates the num of char of the longest line
     *                 index 1: Location that indicates the location of the longest line
     */
    private static List<Object> computeLongestLine(Path dir) {

        AtomicReference<Location> locationOfLongestLineOfFile = new AtomicReference<>(); // Atomic reference that indicates the Location of the longest line
        AtomicInteger maxChars = new AtomicInteger(-1); // AtomicInteger that contains the num of the chars of the longest line, initially set to -1 as no line has been found

        List<Object> longestLineOfFile = new ArrayList<>(); // longest line of the file in the form of a List<Object> where:
                                               //       -> index 0: Integer that indicates the num of char of the longest line
                                               //       -> index 1: Location that indicates the location of the longest line
        try {
            AtomicInteger counterOfLines = new AtomicInteger(0); // Counts the current line

            Files.lines(dir) // Reads the lines of the text file
                    //.parallel()
                    .map(String::toLowerCase) // No case-sensitive
                    .forEach(line -> { // For each line
                        counterOfLines.getAndIncrement(); // Count the number of lines that have been checked
                        if (countChars(line) > maxChars.get()) { // If a new longest line is found
                            maxChars.set(countChars(line)); // Update the value of the amount of chars of the longest line
                            locationOfLongestLineOfFile.set(new Location(dir, counterOfLines.get())); // Updates the location of the longest line
                        }
                    });
        } catch (IOException exception) { // If an error occurs
            exception.printStackTrace(); // Prints the error
        }

        longestLineOfFile.add(maxChars.get()); // Inserts the amount of chars of the longest line of the file
        longestLineOfFile.add(locationOfLongestLineOfFile.get()); // Inserts the location of the longest line of the file

        return longestLineOfFile; // Returns the list with the details of the longest line
    }

    /**
     * Method that counts and returns the number of characters of a line
     *
     * @param line the line of which we want to find out how many chars contains
     * @return the num of chars of the line
     */
    private static int countChars(String line) {

        int countChars = 0; // Number of chars of the line

        BreakIterator it = BreakIterator.getCharacterInstance(Locale.ENGLISH); // Create break iterator to split ENGLISH strings into characters
        it.setText(line); // Setting the text of the BreakIterator with the line of the text file that was given as parameter

        int start = it.first(); // First character of the line
        int end = it.next(); // First character of the second word of the line
        while (end != BreakIterator.DONE) { // While all the chars of the line have been checked

            if (Character.isLetter(line.substring(start, end).charAt(0))) { // if the character is a letter the count, otherwise skip
                countChars++; // Increment the amount of chars
            }

            start = end; // start indicates to the next char
            end = it.next(); // moves on to the next char
        }

        return countChars; // Returns the amount of chars of the line
    }

    /**
     * If multiple lines are identified as longest, the method should return
     * the one that belongs to the file whose name precedes the filename of the other longest line
     * lexicographically, or if the filename is the same, the line which comes first in the file.
     * To compare strings lexicographically, you can use String::compareTo.
     * @param location1 location of the first line
     * @param location2 location of the second line
     * @return the location of which it's filename precedes the other's filename lexicographically
     */
    private static Location compareStringsLexicographically(Location location1, Location location2) {

        if (location1.filepath.getFileName().toString().compareTo(location2.filepath.getFileName().toString()) > 0) // if the first file is lexicographically smaller than the second
            return location2; // Return the second

        return location1; // Otherwise, return the first
    }

    /********************************** Used in method wordWithVowels() **********************************************/

    /**
     * Method used in wordWithVowels() that finds a word with the requested amount of vowels from the current file
     * @param dir directory of the file
     * @param vowels num of requested vowels
     * @return word with the requested amount of vowels or if there isn't a word, the Option.empty() is returned instead
     */
    private static Optional<LocatedWord> computeWordWithVowels(Path dir, int vowels) {

        Optional<LocatedWord> wordWithVowelsOfFile = Optional.empty(); // Indicates the word with the requested amount of vowels
        try {
            Optional<String> wordFound = Files.lines(dir) // Reads the lines of the text file
                    //.parallel()
                    .flatMap(line -> extractWords(line).stream()) // Transforms Stream<String> to Stream<String> where it contains all the words for each line
                    .filter(w -> countVowels(w) == vowels) // Filters the stream so that it only includes the words that have the requested amount of vowels
                    .findFirst(); // Returns the first that has been found

            if(wordFound.isPresent())
                wordWithVowelsOfFile = wordFound.map(s -> new LocatedWord(s, dir));
            else
                wordWithVowelsOfFile = Optional.of(new LocatedWord("", dir));
        } catch (IOException exception) { // If an error occurs
            exception.printStackTrace(); // Print the error
        }

        return wordWithVowelsOfFile; // Returns word with the requested amount of vowels
    }

    /**
     * Method that returns true if the character c is a vowel and false is it is not
     * @param c character
     * @return True or False
     */
    private static boolean checkIfVowel(char c){

        if(c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u') // If it's a vowel
            return true;

        return false; // Otherwise
    }

    /**
     * Method that returns the number of vowels that a word contains
     * @param word String
     * @return number of vowels that a word contains
     */
    private static int countVowels(String word) {

        int countVowels = 0; // counter of the vowels of each word

        for (char c : word.toCharArray()) // For each character in the word
            if (checkIfVowel(c))  //If the word is long enough and has the correct number of vowels
                countVowels++; // Increment the vowel count

        return countVowels; // Return the num of vowels
    }

    /********************************** Used in method wordsEndingWith() ********************************************/

    private static List<LocatedWord> computeWordsEndingWith(Path dir, String suffix, int limit) {

        List<LocatedWord> wordsEndingWithOfFile = new ArrayList<>(); // A list with all the words that end with the requested suffix, initially empty

        try {
            Stream<LocatedWord> wordsWithSuffix = Files.lines(dir) // Reads the lines of the text file
                    //.parallel()
                    .flatMap(line -> extractWords(line).stream()) // Transforms Stream<String> to Stream<String> where it contains all the words for each line
                    .filter(w -> w.endsWith(suffix)) // Filter so that it contains only the words that end with the requested suffix
                    .map(w -> new LocatedWord(w, dir)) // Transforms Stream<String> to Stream<LocatedWord> with all the words that end with the requested suffix in this form
                    .limit(limit); // If more words than the limit appear, remove them

            wordsEndingWithOfFile.addAll(wordsWithSuffix.collect(Collectors.toList())); // Add all the words to the lsit
        } catch (IOException exception) { // If an error occurs
            exception.printStackTrace(); // Print the error
        }

        return wordsEndingWithOfFile; // Return all the words that end with the requested suffix
    }

    /********************************** Used in a lot of methods ****************************************************/

    /**
     * Method that uses BreakIterator to find all the words of the line that is given as a parameter
     *
     * @param line The String that we want to find the words
     * @return a List<String> containing the words
     */
    private static List<String> extractWords(String line) {

        List<String> extractWords = new ArrayList<>(); // List with all the words from line

        BreakIterator it = BreakIterator.getWordInstance(); // BreakIterator to split the string to words
        it.setText(line); // Set the text of the break iterator with the parameter line

        int start = it.first(); // Indicates the index of the first character of the first word of the text
        int end = it.next(); // Indicates the index of the first character of the second word of the text

        while (end != BreakIterator.DONE) { // While there are still words

            String word = line.substring(start, end).toLowerCase(Locale.ENGLISH); // word stores the word given by the iterator
            if (Character.isLetterOrDigit(word.charAt(0))) { // If the word starts with a letter (checks if it's actually a word)
                extractWords.add(word); // Then add it to the list
            }

            start = end; // Proceeds to the next word
            end = it.next(); // Proceeds to the next word
        }

        return extractWords; // Returns the split words
    }
}