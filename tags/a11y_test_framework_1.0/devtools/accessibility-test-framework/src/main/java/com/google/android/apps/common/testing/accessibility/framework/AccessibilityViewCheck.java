/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.apps.common.testing.accessibility.framework;

import android.view.View;

import java.util.List;

/**
 * Base class to check the accessibility of {@code View}s.
 */
public abstract class AccessibilityViewCheck extends AccessibilityCheck {
  public AccessibilityViewCheck() {
  }

  /**
   * Run the check on the view.
   * @param view The view to check. The view should be fully initialized and part of a valid view
   * hierarchy so all of its methods can be called.
   * @return A list of interesting results encountered while running the check. The list will be
   * empty if the check passes without incident.
   */
  public abstract List<AccessibilityViewCheckResult> runCheckOnView(View view);

}
