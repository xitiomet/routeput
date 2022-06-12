package org.openstatic.routeput.util;

import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;

public class RandomQuotes 
{
    ArrayList<String> quotes;

    public RandomQuotes()
    {
        this.quotes = new ArrayList<String>();
        quotes.add("There will be a day when I no longer can do this. THAT DAY IS NOT TODAY.");
        quotes.add("Your friends should motivate and inspire you. Your circle should be well rounded and supportive. Keep it tight. Quality over quantity, always.");
        quotes.add("Don’t make change too complicated. Just begin.");
        quotes.add("YOU are the creator of your own destiny.");
        quotes.add("If you don’t go after what you want, you’ll never have it. If you don’t ask, the answer is always no. If you don’t step forward, you’re always in the same place.");
        quotes.add("This is the start of something beautiful.");
        quotes.add("The most important kind of freedom is to be what you really are. You trade in your reality for a role. You trade in your sense for an act. You give up your ability to feel, and in exchange, put on a mask. There can’t be any large-scale revolution until there’s a personal revolution, on an individual level. It’s got to happen inside first.");
        quotes.add("Don’t let small minds convince you that your dreams are too big.");
        quotes.add("The temptation to quit will be greatest just before you are about to succeed.");
        quotes.add("Never give up on a dream just because of the time it will take to accomplish it. The time will pass anyway.");
        quotes.add("Ask yourself if what you’re doing today is getting you closer to where you want to be tomorrow.");
        quotes.add("The greatest pleasure in life is doing what people say you cannot do.");
        quotes.add("WORKOUT…because talking about how you feel never helps anyway");
        quotes.add("Surround yourself with people that inspire you. ");
        quotes.add("I don’t stop when I’m tired. I stop when I’m done.");
        quotes.add("I am a fighter, not a quitter.");
        quotes.add("You have the patience, the strength and the passion to achieve your ambitions, your goals and your dreams. All you need to do now is TRY.");
        quotes.add("Sometimes it’s not how good you are, but how bad you want it.");
        quotes.add("For a seed to achieve its greatest expression, it must come completely undone. The shell cracks, its insides come out and everything changes. To someone who doesn’t understand growth, it would look like complete destruction.");
        quotes.add("Life is Wonderful. Enjoy it.");
    }

    public String nextQuote()
    {
        int rand = ThreadLocalRandom.current().nextInt(0, this.quotes.size());
        return quotes.get(rand);
    }
}