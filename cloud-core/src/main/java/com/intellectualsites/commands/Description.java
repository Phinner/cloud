//
// MIT License
//
// Copyright (c) 2020 Alexander Söderberg
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//
package com.intellectualsites.commands;

import javax.annotation.Nonnull;

/**
 * {@link com.intellectualsites.commands.arguments.CommandArgument} description
 */
public final class Description {

    /**
     * Empty command description
     */
    private static final Description EMPTY = Description.of("");

    private final String description;

    private Description(@Nonnull final String description) {
        this.description = description;
    }

    /**
     * Get an empty command description
     *
     * @return Command description
     */
    @Nonnull
    public static Description empty() {
        return EMPTY;
    }

    /**
     * Create a command description instance
     *
     * @param string Command description
     * @return Created command description
     */
    @Nonnull
    public static Description of(@Nonnull final String string) {
        return new Description(string);
    }

    /**
     * Get the command description
     *
     * @return Command description
     */
    @Nonnull
    public String getDescription() {
       return this.description;
    }

    /**
     * Get the command description
     *
     * @return Command description
     */
    @Nonnull
    @Override
    public String toString() {
        return this.description;
    }

}
