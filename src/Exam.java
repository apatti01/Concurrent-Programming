import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

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
                uniqueWords.forEach( locatedWord ->
                        System.out.println( locatedWord.word + ":" + locatedWord.filepath ) );
                break;
            case "longestLine":
                checkArguments(args.length == 2, "Usage: java Exam.java longestLine <directory>");
                Location location = longestLine(Paths.get(args[1]));
                System.out.println("Line with highest number of letters found at " + location.filepath + ":" + location.line );
                break;
            case "vowels":
                checkArguments(args.length == 3, "Usage: java Exam.java vowels <directory> <vowels>");
                int vowels = Integer.parseInt(args[2]);
                Optional<LocatedWord> word = wordWithVowels(Paths.get(args[1]), vowels);
                word.ifPresentOrElse(
                        locatedWord -> System.out.println("Found " + locatedWord.word + " in " + locatedWord.filepath),
                        () -> System.out.println("No word found with " + args[2] + " vowels." ) );
                break;
            case "suffix":
                checkArguments(args.length == 4, "Usage: java Exam.java suffix <directory> <suffix> <length>");
                int length = Integer.parseInt(args[3]);
                List<LocatedWord> words = wordsEndingWith(Paths.get(args[1]), args[2], length);
                if( words.size() > length ) {
                    System.out.println( "WARNING: Implementation of wordsEndingWith computes more than " + args[3] + " words!" );
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
     *
     * This method recursively visits a directory to find text files contained in it
     * and its subdirectories (and the subdirectories of these subdirectories,
     * etc.).
     *
     * You must consider only files ending with a ".txt" suffix. You are guaranteed
     * that they will be text files.
     *
     * The method should return a list of LocatedWord objects (defined by the class
     * at the end of this file), where each LocatedWord object should consist of:
     * - a word appearing in every line of a file
     * - the path to the file containing such word.
     *
     * All words appearing on every line of some file must appear in the list: words
     * that can be in the list must be in the list.
     *
     * Words must be considered equal without considering differences between
     * uppercase and lowercase letters. For example, the words "Hello", "hEllo" and
     * "HELLo" must be considered equal to the word "hello".
     *
     * @param dir the directory to search
     * @return a list of words that, within a file inside dir, appear on every line
     */
    private static List<LocatedWord> findWordsCommonToAllLines(Path dir) {
        long t1 = System.currentTimeMillis();
        List<LocatedWord> wordsCommonToAllLines = new ArrayList<>(); // List of all the LocatedWords that appear in all the lines
        ExecutorService executor = Executors.newWorkStealingPool(); // // Contains a pool of available threads
        ExecutorCompletionService<List<LocatedWord>> completionService = new ExecutorCompletionService<>(executor); // Used to manage the tasks of the executor

        try{
            long pendingTasks = Files.walk(dir) // Walks through the directory
                    .filter(Files::isRegularFile) // Checks whether is a regular file
                    .filter(filePath -> filePath.toString().endsWith(".txt")) // Checks whether is a txt file
                    .map(filePath ->
                            completionService.submit(()-> computeWordsCommonToAllLines(filePath))).count(); // Assign each filePath to a new task (thread)

            while (pendingTasks > 0) { // For each task
                wordsCommonToAllLines.addAll(completionService.take().get()); // Add the words that have been found to the list from the task
                pendingTasks--; // Task is completed
            }
        }catch (InterruptedException | ExecutionException | IOException exception) { // If an error occurs
            exception.printStackTrace(); // Prints the error
        }
        try { // Tries to shut down the executor
            executor.shutdown(); // Shutdowns the executor
            executor.awaitTermination(1, TimeUnit.DAYS); // Waits for the executor to terminate
        } catch (InterruptedException exception) { // If an error occurs
            exception.printStackTrace(); // Prints the error
        }
        long t2 = System.currentTimeMillis();
        System.out.println("Elapsed time: " + (t2 - t1) + "ms");
        return wordsCommonToAllLines; // Returns the requested list
    }

    private static List<LocatedWord> computeWordsCommonToAllLines(Path dir) {
        List<LocatedWord> wordsCommonToAllLines = new ArrayList<>(); // List of all the LocatedWords that appear in all the lines
        try{
            Optional<List<String>> words = Files.lines(dir) // Reads the lines of the text file
                    .parallel()
                    .filter(line -> !line.isBlank()) // Filters out the lines that are blank as we don't care about them
                    .map(Exam::extractWords) // Transforms Stream<String> to Stream<List<String>> where it contains lines and all the words for each line
                    .reduce((line1, line2) -> { // Finds the commonWords
                        line2.retainAll(line1); // Intersection of the two lines
                        return line2; // Returns one line instead of two, that has all the words that appear in both of the lines
                    });

            if (words.isPresent()) // If there are common words
                for (String w : words.get()) // For each of the word that is found
                    wordsCommonToAllLines.add(new LocatedWord(w, dir)); // Add the word in the form of LocatedWord to the list

        }catch (IOException exception) { // If an error occurs
            exception.printStackTrace(); // Prints the error
        }

        return wordsCommonToAllLines; // Returns the requested list
    }

    /** Returns the line with the highest number of letters among all the lines
     * present in the text files contained in a directory.
     *
     * This method recursively visits a directory to find all the text files
     * contained in it and its subdirectories (and the subdirectories of these
     * subdirectories, etc.).
     *
     * You must consider only files ending with a ".txt" suffix. You are
     * guaranteed that they will be text files.
     *
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
        throw new UnsupportedOperationException(); // Remove this once you implement the method
    }

    /**
     * Returns an Optional<LocatedWord> (see below) about a word found in the files
     * of the given directory containing the given number of vowels.
     *
     * This method recursively visits a directory to find text files contained in it
     * and its subdirectories (and the subdirectories of these subdirectories,
     * etc.).
     *
     * You must consider only files ending with a ".txt" suffix. You are guaranteed
     * that they will be text files.
     *
     * The method should return an (optional) LocatedWord object (defined by the
     * class at the end of this file), consisting of:
     * - the word found that contains as many vowels as specified by the parameter n (and no more);
     * - the path to the file containing the word.
     *
     * You can consider a letter to be a vowel according to either English or Danish.
     *
     * If a word satisfying the description above can be found, then the method
     * should return an Optional containing the desired LocatedWord. Otherwise, if
     * such a word cannot be found, the method should return Optional.empty().
     *
     * This method should return *as soon as possible*: as soon as a satisfactory
     * word is found, the method should return a result without waiting for the
     * processing of remaining files and/or other data.
     *
     * @param dir the directory to search
     * @param vowels the number of vowels the word must contain
     * @return an optional LocatedWord about a word containing exactly n vowels
     */
    private static Optional<LocatedWord> wordWithVowels(Path dir, int vowels) {
        throw new UnsupportedOperationException(); // Remove this once you implement the method
    }

    /** Returns a list of words found in the given directory ending with the given suffix.
     *
     * This method recursively visits a directory to find text files
     * contained in it and its subdirectories (and the subdirectories of these
     * subdirectories, etc.).
     *
     * You must consider only files ending with a ".txt" suffix. You are
     * guaranteed that they will be text files.
     *
     * The method should return a list of LocatedWord objects (defined by the
     * class at the end of this file), consisting of:
     * - the word found that ends with the given suffix;
     * - the path to the file containing the word.
     *
     * The size of the returned list must not exceed the given limit.
     * Therefore, this method should return *as soon as possible*: if the list
     * reaches the given limit at any point during the computation, no more
     * elements should be added to the list and remaining files and/or other lines
     * should not be analysed.
     *
     * @param dir the directory to search
     * @param suffix the suffix to be searched for
     * @param limit the size limit for the returned list
     * @return a list of locations where the given suffix has been found
     */
    private static List<LocatedWord> wordsEndingWith(Path dir, String suffix, int limit) {
        throw new UnsupportedOperationException(); // Remove this once you implement the method
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


    /**
     * Method that uses BreakIterator to find all the words of the line that is given as a parameter
     * @param line The String that we want to find the words
     * @return a List<String> containing the words
     */
    private static List<String> extractWords(String line) {
        List<String> wordsFromLine = new ArrayList<>(); // List with all the words from line
        BreakIterator it = BreakIterator.getWordInstance(); // BreakIterator to split the string to words
        it.setText(line); // Set the text of the break iterator with the parameter line

        int start = it.first(); // Indicates the index of the first character of the first word of the text
        int end = it.next(); // Indicates the index of the first character of the second word of the text

        while (end != BreakIterator.DONE) { // While there are still words
            String word = line.substring(start, end); // word stores the word given by the iterator
            if (Character.isLetterOrDigit(word.charAt(0))) { // If the word starts with a letter (checks if it's actually a word)
                wordsFromLine.add(word); // Then add it to the list
            }
            start = end; // Proceeds to the next word
            end = it.next(); // Proceeds to the next word
        }

        return wordsFromLine; // Returns the split words
    }
}