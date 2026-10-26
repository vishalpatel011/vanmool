package com.vanmool.userauthservice.controllers;

import com.vanmool.userauthservice.dtos.PasswordResetConfirmDto;
import com.vanmool.userauthservice.dtos.PasswordResetRequestDto;
import com.vanmool.userauthservice.services.PasswordResetService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

// Password reset (PRD 1.4). Open to everyone (/auth/**): the person has forgotten their password, so they have no token.
@RestController
@RequestMapping("/auth/password-reset")
public class PasswordResetController {
    // What PasswordResetService puts in a link: 32 random bytes in Base64url
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    // 202 for every valid email, with or without an account: the answer must not tell which emails are registered
    @PostMapping
    public ResponseEntity<Void> requestReset(@Valid @RequestBody PasswordResetRequestDto request) {
        passwordResetService.requestReset(request.getEmail());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetConfirmDto request) {
        passwordResetService.resetPassword(request.getToken(), request.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    // The page the emailed link opens. Opening it changes nothing: mail scanners open links by themselves, and that
    // must not use up the link. The form then sends the new password to POST /confirm. A web or mobile client of
    // its own can replace this page (PASSWORD_RESET_LINK).
    // The token is only written into the page when it has the token's exact shape, so nothing else can be injected.
    // no-referrer: the address with the token is never sent on to another site
    @GetMapping(value = "/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> resetPage(@RequestParam(name = "token", required = false) String token) {
        String body = token != null && TOKEN.matcher(token).matches() ? FORM.formatted(token) : INVALID_LINK;
        return ResponseEntity.ok().header("Referrer-Policy", "no-referrer").body(PAGE.formatted(body));
    }

    private static final String PAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Vanmool: choose a new password</title>
            </head>
            <body style="font-family: sans-serif; max-width: 26rem; margin: 3rem auto; padding: 0 1rem">
            <h1>Choose a new password</h1>
            %s
            </body>
            </html>
            """;

    private static final String INVALID_LINK = """
            <p>This link is not complete. Please open the link from the email again, or ask for a new one.</p>""";

    private static final String FORM = """
            <form id="reset">
            <p><label>New password (at least 8 characters)<br>
            <input type="password" id="password" minlength="8" required autocomplete="new-password"></label></p>
            <p><label>The same password again<br>
            <input type="password" id="repeat" minlength="8" required autocomplete="new-password"></label></p>
            <p><button>Save the new password</button></p>
            </form>
            <p id="result" role="status"></p>
            <script>
            document.getElementById("reset").addEventListener("submit", async (event) => {
              event.preventDefault();
              const result = document.getElementById("result");
              const password = document.getElementById("password").value;
              if (password !== document.getElementById("repeat").value) {
                result.textContent = "The two passwords are not the same.";
                return;
              }
              const response = await fetch("confirm", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ token: "%s", newPassword: password })
              });
              if (response.status === 204) {
                event.target.remove();
                result.textContent = "Your password has been changed. You can sign in with it now.";
              } else {
                result.textContent = (await response.json()).message;
              }
            });
            </script>""";
}
